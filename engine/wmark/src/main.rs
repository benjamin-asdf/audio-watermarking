//! wmark embed <input> <output.wav|flac> <payload-hex24>
//! wmark detect <input>      (tempo/pitch changes are searched for; --speed is accepted and ignored)
//!
//! Prints one JSON object on stdout. Errors: {"error": "..."} on stdout, exit code 1.

use std::path::Path;
use std::process::ExitCode;

fn usage() -> anyhow::Result<serde_json::Value> {
    anyhow::bail!("usage: wmark embed <input> <output.wav|flac> <payload-hex24> | wmark detect <input>")
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["embed", input, output, payload] => wmark::embed(Path::new(input), Path::new(output), payload)
            .and_then(|r| Ok(serde_json::to_value(r)?)),
        ["detect", "--speed", input] | ["detect", input] => wmark::detect(Path::new(input))
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
