use std::collections::HashMap;
use std::fs;
use std::path::Path;

use crate::{Error, Result};

#[derive(Debug, Clone)]
pub struct RomajiTable {
    by_kana: HashMap<String, String>,
    max_kana: usize,
}

impl RomajiTable {
    pub fn load(path: &Path) -> Result<Self> {
        let contents = fs::read_to_string(path).map_err(|source| Error::Read {
            path: path.to_path_buf(),
            source,
        })?;
        Self::parse(&contents)
    }

    pub fn parse(contents: &str) -> Result<Self> {
        let mut by_kana: HashMap<String, String> = HashMap::new();
        for line in contents.lines() {
            let line = line.trim_end();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut fields = line.split('\t');
            let romaji = fields.next().unwrap_or("").trim();
            let kana = fields.next().unwrap_or("").trim();
            if kana.is_empty() || romaji.is_empty() {
                continue;
            }
            let candidate = RomajiCandidate::score(romaji);
            match by_kana.get(kana) {
                Some(existing) if RomajiCandidate::score(existing) <= candidate => {}
                _ => {
                    by_kana.insert(kana.to_string(), romaji.to_string());
                }
            }
        }
        if by_kana.is_empty() {
            return Err(Error::Romaji(
                "the romaji table did not contain any kana mappings".to_string(),
            ));
        }
        let max_kana = by_kana
            .keys()
            .map(|kana| kana.chars().count())
            .max()
            .unwrap_or(1);
        Ok(Self { by_kana, max_kana })
    }

    pub fn to_romaji(&self, reading: &str) -> Result<String> {
        let chars: Vec<char> = reading.chars().collect();
        let mut out = String::new();
        let mut index = 0;
        while index < chars.len() {
            if chars[index] == 'っ' {
                if let Some((_, next)) = self.match_at(&chars, index + 1) {
                    if let Some(consonant) = next
                        .chars()
                        .next()
                        .filter(|ch| ch.is_ascii_lowercase() && !is_vowel(*ch))
                    {
                        out.push(consonant);
                        index += 1;
                        continue;
                    }
                }
                out.push_str("ltu");
                index += 1;
                continue;
            }

            let Some((length, romaji)) = self.match_at(&chars, index) else {
                return Err(Error::Romaji(format!(
                    "no romaji mapping for {:?} at character {}",
                    chars[index], index
                )));
            };
            if romaji.ends_with('n') {
                let next_starts_ambiguous = chars
                    .get(index + length)
                    .and_then(|next| self.leading_romaji(*next))
                    .is_some_and(|next| starts_with_vowel_or_y(&next));
                out.push_str(romaji);
                if next_starts_ambiguous {
                    out.push('\'');
                }
            } else {
                out.push_str(romaji);
            }
            index += length;
        }
        Ok(out)
    }

    fn match_at<'a>(&'a self, chars: &[char], index: usize) -> Option<(usize, &'a str)> {
        if index >= chars.len() {
            return None;
        }
        let limit = self.max_kana.min(chars.len() - index);
        for length in (1..=limit).rev() {
            let candidate: String = chars[index..index + length].iter().collect();
            if let Some(romaji) = self.by_kana.get(&candidate) {
                return Some((length, romaji.as_str()));
            }
        }
        None
    }

    fn leading_romaji(&self, kana: char) -> Option<String> {
        let mut buffer = [0u8; 4];
        self.by_kana.get(kana.encode_utf8(&mut buffer)).cloned()
    }
}

#[derive(Debug, Clone, Copy)]
struct RomajiCandidate {
    plain_lowercase: bool,
    length: usize,
}

impl RomajiCandidate {
    fn score(romaji: &str) -> Self {
        Self {
            plain_lowercase: romaji.chars().all(|ch| ch.is_ascii_lowercase()),
            length: romaji.chars().count(),
        }
    }

    fn ranked(self) -> (u8, usize) {
        (!self.plain_lowercase as u8, self.length)
    }
}

impl PartialOrd for RomajiCandidate {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for RomajiCandidate {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.ranked().cmp(&other.ranked())
    }
}

impl PartialEq for RomajiCandidate {
    fn eq(&self, other: &Self) -> bool {
        self.ranked() == other.ranked()
    }
}

impl Eq for RomajiCandidate {}

fn starts_with_vowel_or_y(romaji: &str) -> bool {
    romaji
        .chars()
        .next()
        .is_some_and(|ch| is_vowel(ch) || ch == 'y')
}

fn is_vowel(ch: char) -> bool {
    matches!(ch, 'a' | 'i' | 'u' | 'e' | 'o')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table() -> RomajiTable {
        RomajiTable::parse(
            ",\t、\n.\t。\n-\tー\nka\tか\nki\tき\nku\tく\nke\tけ\nko\tこ\nga\tが\n\
             kyo\tきょ\nkyou\tきょう\nha\tは\nhi\tひ\nfu\tふ\nhe\tへ\nho\tほ\n\
             n\tん\nni\tに\nchi\tち\nwa\tわ\nsho\tしょ\nshi\tし\nyo\tよ\nu\tう\n\
             ltu\tっ\n",
        )
        .unwrap()
    }

    #[test]
    fn longest_kana_wins() {
        assert_eq!(table().to_romaji("きょう").unwrap(), "kyou");
    }

    #[test]
    fn inserts_apostrophe_before_ambiguous_vowel() {
        assert_eq!(table().to_romaji("しんよう").unwrap(), "shin'you");
    }

    #[test]
    fn rejects_unknown_kana() {
        assert!(table().to_romaji("ゎ").is_err());
    }

    #[test]
    fn sokuon_doubles_the_next_consonant() {
        assert_eq!(table().to_romaji("がっこう").unwrap(), "gakkou");
    }
}
