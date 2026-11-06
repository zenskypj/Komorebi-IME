//! Platform-neutral core for the Linux frontends.
//!
//! Today this is the M0 foundation: it opens the patched Mozc engine, owns the profile key, and
//! turns a hiragana reading into Mozc candidates. Ranking, pins, dictionaries, and the neural
//! client grow on top of the same session model.

mod engine;
mod paths;
mod romaji;

pub use engine::{Candidate, Conversion, Engine, Session, candidates, preedit_text};
pub use paths::Paths;
pub use romaji::RomajiTable;

use std::path::PathBuf;

#[derive(Debug)]
pub enum Error {
    Io(std::io::Error),
    Mozc(String),
    Decode(prost::DecodeError),
    Read {
        path: PathBuf,
        source: std::io::Error,
    },
    Write {
        path: PathBuf,
        source: std::io::Error,
    },
    Romaji(String),
    MissingData(String),
    SessionNotCreated,
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Io(source) => write!(f, "{source}"),
            Error::Mozc(message) => write!(f, "Mozc engine error: {message}"),
            Error::Decode(source) => write!(f, "invalid Mozc protocol response: {source}"),
            Error::Read { path, source } => write!(f, "cannot read {}: {source}", path.display()),
            Error::Write { path, source } => write!(f, "cannot write {}: {source}", path.display()),
            Error::Romaji(message) => write!(f, "romaji conversion failed: {message}"),
            Error::MissingData(message) => write!(f, "{message}"),
            Error::SessionNotCreated => write!(f, "Mozc did not return a session id"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Io(source) => Some(source),
            Error::Decode(source) => Some(source),
            Error::Read { source, .. } | Error::Write { source, .. } => Some(source),
            _ => None,
        }
    }
}

impl From<std::io::Error> for Error {
    fn from(source: std::io::Error) -> Self {
        Error::Io(source)
    }
}

impl From<prost::DecodeError> for Error {
    fn from(source: prost::DecodeError) -> Self {
        Error::Decode(source)
    }
}

pub type Result<T> = std::result::Result<T, Error>;
