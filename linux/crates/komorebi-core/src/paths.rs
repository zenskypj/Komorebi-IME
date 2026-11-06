use std::env;
use std::path::{Path, PathBuf};

const REPO_MANIFEST: &str = env!("CARGO_MANIFEST_DIR");

#[derive(Debug, Clone)]
pub struct Paths {
    pub profile_dir: PathBuf,
    pub data_file: PathBuf,
    pub key_file: PathBuf,
    pub romaji_table: PathBuf,
}

impl Paths {
    pub fn discover() -> Self {
        Self::with_overrides(None, None, None)
    }

    pub fn with_overrides(
        profile_dir: Option<PathBuf>,
        data_file: Option<PathBuf>,
        romaji_table: Option<PathBuf>,
    ) -> Self {
        let data_root = env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home().join(".local").join("share"))
            .join("komorebi");

        let profile_dir = profile_dir
            .or_else(|| env::var_os("KOMOREBI_PROFILE_DIR").map(PathBuf::from))
            .unwrap_or_else(|| data_root.join("mozc"));

        let key_file = env::var_os("KOMOREBI_KEY_FILE")
            .map(PathBuf::from)
            .unwrap_or_else(|| data_root.join("key"));

        let data_file = data_file
            .or_else(|| env::var_os("KOMOREBI_MOZC_DATA").map(PathBuf::from))
            .or_else(|| first_existing(data_candidates()))
            .unwrap_or_else(|| PathBuf::from("/usr/share/komorebi/mozc.data"));

        let romaji_table = romaji_table
            .or_else(|| env::var_os("KOMOREBI_ROMAJI_TABLE").map(PathBuf::from))
            .or_else(|| first_existing(romaji_candidates()))
            .unwrap_or_else(|| PathBuf::from("/usr/share/komorebi/romaji.tsv"));

        Self {
            profile_dir,
            data_file,
            key_file,
            romaji_table,
        }
    }
}

fn home() -> PathBuf {
    env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/tmp"))
}

fn repo_root() -> PathBuf {
    Path::new(REPO_MANIFEST).join("..").join("..").join("..")
}

fn data_candidates() -> Vec<PathBuf> {
    let mut candidates = vec![
        PathBuf::from("/usr/share/komorebi/mozc.data"),
        PathBuf::from("linux/data/mozc.data"),
    ];
    if cfg!(debug_assertions) {
        candidates.push(repo_root().join("linux/data/mozc.data"));
        candidates.push(repo_root().join("mozc/src/main/assets/mozc.data"));
    }
    candidates
}

fn romaji_candidates() -> Vec<PathBuf> {
    let mut candidates = vec![
        PathBuf::from("/usr/share/komorebi/romaji.tsv"),
        PathBuf::from("linux/data/romaji.tsv"),
    ];
    if cfg!(debug_assertions) {
        candidates.push(repo_root().join("linux/data/romaji.tsv"));
        candidates.push(repo_root().join("app/src/main/assets/zenzai/romaji.tsv"));
    }
    candidates
}

fn first_existing(candidates: impl IntoIterator<Item = PathBuf>) -> Option<PathBuf> {
    candidates.into_iter().find(|candidate| candidate.is_file())
}
