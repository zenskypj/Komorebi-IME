use std::path::PathBuf;
use std::process::ExitCode;

use komorebi_core::{Engine, Paths};

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("komorebictl: {message}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let mut profile = None;
    let mut data = None;
    let mut romaji = None;
    let mut command = None;
    let mut command_args = Vec::new();

    let mut args = std::env::args().skip(1).peekable();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-h" | "--help" => {
                print_help();
                return Ok(());
            }
            "--profile" => profile = Some(PathBuf::from(next_value(&mut args, "--profile")?)),
            "--data" => data = Some(PathBuf::from(next_value(&mut args, "--data")?)),
            "--romaji" => romaji = Some(PathBuf::from(next_value(&mut args, "--romaji")?)),
            other if other.starts_with('-') => {
                return Err(format!("unknown option {other}; try --help"));
            }
            other => {
                command = Some(other.to_string());
                command_args.extend(args);
                break;
            }
        }
    }

    let Some(command) = command else {
        print_help();
        return Ok(());
    };

    let paths = Paths::with_overrides(profile, data, romaji);
    match command.as_str() {
        "convert" => convert(&paths, &command_args),
        "data-version" => {
            let engine = Engine::open(&paths).map_err(|error| error.to_string())?;
            println!("{}", engine.data_version());
            Ok(())
        }
        other => Err(format!("unknown command {other}; try --help")),
    }
}

fn convert(paths: &Paths, args: &[String]) -> Result<(), String> {
    let mut reading = String::new();
    let mut limit = usize::MAX;
    let mut commit = false;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--limit" => {
                index += 1;
                let value = args.get(index).ok_or("--limit needs a value")?;
                limit = value
                    .parse()
                    .map_err(|_| format!("invalid --limit value {value}"))?;
            }
            "--commit" => commit = true,
            value => {
                if !reading.is_empty() {
                    reading.push(' ');
                }
                reading.push_str(value);
            }
        }
        index += 1;
    }
    if reading.is_empty() {
        return Err("convert needs a hiragana reading".to_string());
    }

    let engine = Engine::open(paths).map_err(|error| error.to_string())?;
    let session = engine.session().map_err(|error| error.to_string())?;
    let conversion = session
        .convert(&reading)
        .map_err(|error| error.to_string())?;
    let committed = if commit {
        Some(session.submit().map_err(|error| error.to_string())?)
    } else {
        None
    };
    session.close().map_err(|error| error.to_string())?;

    println!("Mozc data: {}", engine.data_version());
    if !conversion.preedit.is_empty() {
        println!("Preedit: {}", conversion.preedit);
    }
    if let Some(committed) = committed {
        println!("Committed: {committed}");
    }
    if conversion.candidates.is_empty() {
        println!("No candidates for {reading:?}.");
        return Ok(());
    }
    for (position, candidate) in conversion.candidates.iter().take(limit).enumerate() {
        println!("{:>3}  {}", position + 1, candidate.value);
    }
    Ok(())
}

fn next_value(args: &mut impl Iterator<Item = String>, option: &str) -> Result<String, String> {
    args.next().ok_or_else(|| format!("{option} needs a value"))
}

fn print_help() {
    println!(
        "\
komorebictl {version}

Usage:
  komorebictl [OPTIONS] convert <hiragana or romaji> [--limit N] [--commit]
  komorebictl [OPTIONS] data-version

Options:
  --profile DIR   Mozc profile directory
  --data FILE     mozc.data file
  --romaji FILE   romaji table (romaji<TAB>kana)
  -h, --help      show this help
",
        version = env!("CARGO_PKG_VERSION")
    );
}
