use std::io::{self, Read};

use garland_core::blossom_upload::{execute_blossom_upload, BlossomUploadRequest};
use serde::Serialize;

#[derive(Serialize)]
struct BlossomUploadResponseEnvelope {
    ok: bool,
    result: Option<serde_json::Value>,
    error: Option<String>,
}

fn main() {
    let mut input = String::new();
    let response = match io::stdin().read_to_string(&mut input) {
        Ok(_) => match serde_json::from_str::<BlossomUploadRequest>(&input) {
            Ok(request) => match execute_blossom_upload(&request) {
                Ok(result) => BlossomUploadResponseEnvelope {
                    ok: true,
                    result: Some(serde_json::to_value(result).expect("result should serialize")),
                    error: None,
                },
                Err(error) => BlossomUploadResponseEnvelope {
                    ok: false,
                    result: None,
                    error: Some(error.to_string()),
                },
            },
            Err(error) => BlossomUploadResponseEnvelope {
                ok: false,
                result: None,
                error: Some(format!("invalid blossom-upload request JSON: {error}")),
            },
        },
        Err(error) => BlossomUploadResponseEnvelope {
            ok: false,
            result: None,
            error: Some(format!("failed to read stdin: {error}")),
        },
    };

    println!(
        "{}",
        serde_json::to_string(&response).expect("response should serialize")
    );
}
