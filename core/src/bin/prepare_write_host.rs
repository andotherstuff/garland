use std::io::{self, Read};

use garland_core::mvp_write::{prepare_single_block_write, PrepareWriteRequest};
use serde::Serialize;

#[derive(Serialize)]
struct WritePlanResponse {
    ok: bool,
    plan: Option<serde_json::Value>,
    error: Option<String>,
}

fn main() {
    let mut input = String::new();
    let response = match io::stdin().read_to_string(&mut input) {
        Ok(_) => match serde_json::from_str::<PrepareWriteRequest>(&input) {
            Ok(request) => match prepare_single_block_write(&request) {
                Ok(plan) => WritePlanResponse {
                    ok: true,
                    plan: Some(serde_json::to_value(plan).expect("plan should serialize")),
                    error: None,
                },
                Err(error) => WritePlanResponse {
                    ok: false,
                    plan: None,
                    error: Some(error.to_string()),
                },
            },
            Err(error) => WritePlanResponse {
                ok: false,
                plan: None,
                error: Some(format!("invalid prepare-write request JSON: {error}")),
            },
        },
        Err(error) => WritePlanResponse {
            ok: false,
            plan: None,
            error: Some(format!("failed to read stdin: {error}")),
        },
    };

    println!(
        "{}",
        serde_json::to_string(&response).expect("response should serialize")
    );
}
