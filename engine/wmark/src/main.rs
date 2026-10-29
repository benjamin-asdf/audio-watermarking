//! wmark embed <input> <output.wav|flac|aiff> <payload-hex32>
//! wmark detect [--speed] <input>
//!
//! Prints one JSON object on stdout. Errors: {"error": "..."} on stdout, exit code 1.

use std::path::Path;
use std::process::ExitCode;

fn usage() -> anyhow::Result<serde_json::Value> {
    anyhow::bail!("usage: wmark embed <input> <output> <payload-hex32> | wmark detect [--speed] <input>")
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let tools = wmark::Tools::locate();
    let result = match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["embed", input, output, payload] => wmark::embed(&tools, Path::new(input), Path::new(output), payload)
            .and_then(|r| Ok(serde_json::to_value(r)?)),
        ["detect", "--speed", input] => wmark::detect(&tools, Path::new(input), true)
            .and_then(|r| Ok(serde_json::to_value(r)?)),
        ["detect", input] => wmark::detect(&tools, Path::new(input), false)
            .and_then(|r| Ok(serde_json::to_value(r)?)),
        _ => usage(),
    };
    match result {
        Ok(v) => {
            println!("{v}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            println!("{}", serde_json::json!({ "error": format!("{e:#}") }));
            ExitCode::FAILURE
        }
    }
}
