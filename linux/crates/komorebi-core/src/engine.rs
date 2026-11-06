use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;

use komorebi_mozc::proto::commands::{
    Command, CompositionMode, Input, KeyEvent, Output, SessionCommand, input, key_event,
    session_command,
};
use prost::Message;

use crate::paths::Paths;
use crate::romaji::RomajiTable;
use crate::{Error, Result};

pub struct Engine {
    inner: komorebi_mozc::ffi::Engine,
    romaji: RomajiTable,
    data_version: String,
}

impl Engine {
    pub fn open(paths: &Paths) -> Result<Self> {
        if !paths.data_file.is_file() {
            return Err(Error::MissingData(format!(
                "mozc.data was not found at {}; run scripts/build_mozc_linux.sh",
                paths.data_file.display()
            )));
        }
        ensure_private_dir(&paths.profile_dir)?;
        let key = load_or_create_key(&paths.key_file)?;
        let inner = komorebi_mozc::ffi::Engine::new(&paths.profile_dir, &paths.data_file, &key)
            .map_err(Error::Mozc)?;
        let data_version = inner.data_version();
        let romaji = RomajiTable::load(&paths.romaji_table)?;
        Ok(Self {
            inner,
            romaji,
            data_version,
        })
    }

    pub fn data_version(&self) -> &str {
        &self.data_version
    }

    pub fn session(&self) -> Result<Session<'_>> {
        let output = self.eval(Input {
            r#type: input::CommandType::CreateSession as i32,
            ..Default::default()
        })?;
        let id = output.id.ok_or(Error::SessionNotCreated)?;
        let session = Session { engine: self, id };
        session.switch_composition_mode(CompositionMode::Hiragana)?;
        Ok(session)
    }

    fn eval(&self, input: Input) -> Result<Output> {
        let command = Command {
            input,
            output: None,
        };
        let encoded = command.encode_to_vec();
        let decoded = self.inner.eval(&encoded).map_err(Error::Mozc)?;
        let response = Command::decode(decoded.as_slice())?;
        Ok(response.output.unwrap_or_default())
    }
}

pub struct Session<'a> {
    engine: &'a Engine,
    id: u64,
}

impl Session<'_> {
    pub fn id(&self) -> u64 {
        self.id
    }

    pub fn convert(&self, reading: &str) -> Result<Conversion> {
        let romaji = if reading.chars().all(|ch| ch.is_ascii()) {
            reading.to_string()
        } else {
            self.engine.romaji.to_romaji(reading)?
        };
        if romaji.is_empty() {
            return Ok(Conversion {
                candidates: Vec::new(),
                preedit: String::new(),
            });
        }
        for ch in romaji.chars() {
            if !ch.is_ascii() {
                return Err(Error::Romaji(format!(
                    "the input produced a non-ASCII key {ch:?}"
                )));
            }
            self.send_key(KeyEvent {
                key_code: Some(ch as u32),
                ..Default::default()
            })?;
        }
        let output = self.send_key(KeyEvent {
            special_key: Some(key_event::SpecialKey::Space as i32),
            ..Default::default()
        })?;
        Ok(Conversion {
            candidates: candidates(&output),
            preedit: preedit_text(&output),
        })
    }

    pub fn send_key(&self, key: KeyEvent) -> Result<Output> {
        self.engine.eval(Input {
            r#type: input::CommandType::SendKey as i32,
            id: Some(self.id),
            key: Some(key),
            ..Default::default()
        })
    }

    pub fn send_command(&self, command: SessionCommand) -> Result<Output> {
        self.engine.eval(Input {
            r#type: input::CommandType::SendCommand as i32,
            id: Some(self.id),
            command: Some(command),
            ..Default::default()
        })
    }

    pub fn switch_composition_mode(&self, mode: CompositionMode) -> Result<()> {
        self.send_command(SessionCommand {
            r#type: session_command::CommandType::SwitchCompositionMode as i32,
            composition_mode: Some(mode as i32),
            ..Default::default()
        })?;
        Ok(())
    }

    pub fn submit(&self) -> Result<String> {
        let output = self.send_command(SessionCommand {
            r#type: session_command::CommandType::Submit as i32,
            ..Default::default()
        })?;
        Ok(output.result.map(|result| result.value).unwrap_or_default())
    }

    pub fn close(self) -> Result<()> {
        let _ = self.send_command(SessionCommand {
            r#type: session_command::CommandType::Revert as i32,
            ..Default::default()
        });
        let _ = self.engine.eval(Input {
            r#type: input::CommandType::SyncData as i32,
            id: Some(self.id),
            ..Default::default()
        });
        let _ = self.engine.eval(Input {
            r#type: input::CommandType::DeleteSession as i32,
            id: Some(self.id),
            ..Default::default()
        });
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub value: String,
    pub id: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Conversion {
    pub candidates: Vec<Candidate>,
    pub preedit: String,
}

pub fn candidates(output: &Output) -> Vec<Candidate> {
    let mut result: Vec<Candidate> = Vec::new();
    let mut seen = std::collections::HashSet::new();

    if let Some(list) = &output.all_candidate_words {
        for word in &list.candidates {
            push_candidate(&mut result, &mut seen, word.value.as_deref(), word.id);
        }
    }
    if let Some(window) = &output.candidate_window {
        for word in &window.candidate {
            push_candidate(&mut result, &mut seen, Some(word.value.as_str()), word.id);
        }
    }
    result
}

pub fn preedit_text(output: &Output) -> String {
    output
        .preedit
        .as_ref()
        .map(|preedit| {
            preedit
                .segment
                .iter()
                .filter_map(|segment| {
                    if segment.value.is_empty() {
                        None
                    } else {
                        Some(segment.value.as_str())
                    }
                })
                .collect::<String>()
        })
        .unwrap_or_default()
}

fn push_candidate(
    result: &mut Vec<Candidate>,
    seen: &mut std::collections::HashSet<String>,
    value: Option<&str>,
    id: Option<i32>,
) {
    let Some(value) = value else {
        return;
    };
    if value.is_empty() || !seen.insert(value.to_string()) {
        return;
    }
    result.push(Candidate {
        value: value.to_string(),
        id,
    });
}

fn ensure_private_dir(path: &Path) -> Result<()> {
    fs::create_dir_all(path).map_err(|source| Error::Write {
        path: path.to_path_buf(),
        source,
    })?;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700)).map_err(|source| Error::Write {
        path: path.to_path_buf(),
        source,
    })
}

fn load_or_create_key(path: &Path) -> Result<[u8; 32]> {
    match fs::read(path) {
        Ok(bytes) => {
            return <[u8; 32]>::try_from(bytes.as_slice()).map_err(|_| {
                Error::Mozc(format!(
                    "{} must contain exactly 32 bytes for the Mozc profile key",
                    path.display()
                ))
            });
        }
        Err(source) if source.kind() == io::ErrorKind::NotFound => {}
        Err(source) => {
            return Err(Error::Read {
                path: path.to_path_buf(),
                source,
            });
        }
    }

    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    ensure_private_dir(parent)?;

    let mut key = [0u8; 32];
    File::open("/dev/urandom")
        .and_then(|mut random| random.read_exact(&mut key))
        .map_err(|source| Error::Write {
            path: path.to_path_buf(),
            source,
        })?;

    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .map_err(|source| Error::Write {
            path: path.to_path_buf(),
            source,
        })?;
    file.write_all(&key)
        .and_then(|()| file.sync_all())
        .map_err(|source| Error::Write {
            path: path.to_path_buf(),
            source,
        })?;
    Ok(key)
}

#[cfg(test)]
mod tests {
    use super::*;
    use komorebi_mozc::proto::commands::{
        CandidateList, CandidateWindow, CandidateWord, Preedit, candidate_window, preedit,
    };

    #[test]
    fn candidates_keep_order_and_drop_duplicates() {
        let mut output = Output::default();
        output.all_candidate_words = Some(CandidateList {
            candidates: vec![
                CandidateWord {
                    value: Some("今日".to_string()),
                    id: Some(1),
                    ..Default::default()
                },
                CandidateWord {
                    value: Some("京".to_string()),
                    id: Some(2),
                    ..Default::default()
                },
            ],
            ..Default::default()
        });
        output.candidate_window = Some(CandidateWindow {
            size: 2,
            position: 0,
            candidate: vec![candidate_window::Candidate {
                index: 0,
                value: "今日".to_string(),
                id: Some(1),
                ..Default::default()
            }],
            ..Default::default()
        });

        let candidates = candidates(&output);
        assert_eq!(candidates.len(), 2);
        assert_eq!(candidates[0].value, "今日");
        assert_eq!(candidates[1].value, "京");
    }

    #[test]
    fn preedit_joins_segments() {
        let mut output = Output::default();
        output.preedit = Some(Preedit {
            cursor: 4,
            segment: vec![
                preedit::Segment {
                    annotation: 0,
                    value: "今日".to_string(),
                    value_length: 2,
                    key: Some("きょう".to_string()),
                },
                preedit::Segment {
                    annotation: 0,
                    value: "は".to_string(),
                    value_length: 1,
                    key: Some("は".to_string()),
                },
            ],
            ..Default::default()
        });
        assert_eq!(preedit_text(&output), "今日は");
    }
}
