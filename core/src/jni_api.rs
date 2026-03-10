use base64::Engine as _;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

use crate::blossom_upload::{execute_blossom_upload, BlossomUploadRequest};
use crate::commit_chain::{
    prepare_commit_chain_snapshot, read_directory_entries, resolve_commit_chain_head,
    PrepareCommitChainRequest, ReadDirectoryEntriesRequest, ResolveCommitChainHeadRequest,
};
use crate::identity::{derive_nostr_identity, generate_identity};
use crate::mvp_write::{
    prepare_single_block_write, recover_single_block_read, PrepareWriteRequest, RecoverReadRequest,
};
use crate::nostr_event::{sign_blossom_upload_auth_event, sign_custom_event, UnsignedEvent};

#[derive(Serialize)]
struct IdentityResponse {
    ok: bool,
    nsec: Option<String>,
    private_key_hex: Option<String>,
    error: Option<String>,
}

#[derive(Serialize)]
struct GeneratedIdentityResponse {
    ok: bool,
    mnemonic: Option<String>,
    nsec: Option<String>,
    private_key_hex: Option<String>,
    error: Option<String>,
}

#[derive(Serialize)]
struct WritePlanResponse {
    ok: bool,
    plan: Option<serde_json::Value>,
    error: Option<String>,
}

#[derive(Serialize)]
struct ReadRecoveryResponse {
    ok: bool,
    content_b64: Option<String>,
    error: Option<String>,
}

#[derive(Serialize)]
struct SignEventResponse {
    ok: bool,
    event: Option<serde_json::Value>,
    error: Option<String>,
}

#[derive(Serialize)]
struct BlossomUploadResponseEnvelope {
    ok: bool,
    result: Option<serde_json::Value>,
    error: Option<String>,
}

#[derive(Serialize)]
struct CommitChainResponse {
    ok: bool,
    result: Option<serde_json::Value>,
    error: Option<String>,
}

#[derive(Deserialize)]
struct PrepareWriteJson {
    private_key_hex: String,
    display_name: String,
    mime_type: String,
    created_at: u64,
    content_b64: String,
    servers: Vec<String>,
    #[serde(default)]
    document_id: Option<String>,
    #[serde(default)]
    previous_event_id: Option<String>,
}

#[derive(Deserialize)]
struct RecoverReadJson {
    private_key_hex: String,
    document_id: String,
    block_index: u32,
    encrypted_block_b64: String,
}

#[derive(Deserialize)]
struct SignEventJson {
    private_key_hex: String,
    created_at: u64,
    kind: u64,
    tags: Vec<Vec<String>>,
    content: String,
}

#[derive(Deserialize)]
struct BlossomUploadAuthJson {
    private_key_hex: String,
    share_id_hex: String,
    server_url: String,
    size_bytes: u64,
    created_at: u64,
    expiration: u64,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_deriveIdentity(
    mut env: JNIEnv,
    _class: JClass,
    mnemonic: JString,
    passphrase: JString,
) -> jstring {
    let mnemonic: String = env
        .get_string(&mnemonic)
        .map(|value| value.into())
        .unwrap_or_default();
    let passphrase: String = env
        .get_string(&passphrase)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match derive_nostr_identity(&mnemonic, &passphrase) {
        Ok(identity) => IdentityResponse {
            ok: true,
            nsec: Some(identity.nsec),
            private_key_hex: Some(identity.private_key_hex),
            error: None,
        },
        Err(error) => IdentityResponse {
            ok: false,
            nsec: None,
            private_key_hex: None,
            error: Some(error.to_string()),
        },
    };

    let payload = serde_json::to_string(&response).unwrap_or_else(|err| {
        format!(
            "{{\"ok\":false,\"nsec\":null,\"private_key_hex\":null,\"error\":\"{}\"}}",
            err
        )
    });

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_generateIdentity(
    mut env: JNIEnv,
    _class: JClass,
    passphrase: JString,
) -> jstring {
    let passphrase: String = env
        .get_string(&passphrase)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match generate_identity(&passphrase) {
        Ok(identity) => GeneratedIdentityResponse {
            ok: true,
            mnemonic: Some(identity.mnemonic),
            nsec: Some(identity.nsec),
            private_key_hex: Some(identity.private_key_hex),
            error: None,
        },
        Err(error) => GeneratedIdentityResponse {
            ok: false,
            mnemonic: None,
            nsec: None,
            private_key_hex: None,
            error: Some(error.to_string()),
        },
    };

    let payload = serde_json::to_string(&response).unwrap_or_else(|err| {
        format!(
            "{{\"ok\":false,\"mnemonic\":null,\"nsec\":null,\"private_key_hex\":null,\"error\":\"{}\"}}",
            err
        )
    });

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_prepareSingleBlockWrite(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<PrepareWriteJson>(&request_json) {
        Ok(request) => {
            let request = PrepareWriteRequest {
                private_key_hex: request.private_key_hex,
                display_name: request.display_name,
                mime_type: request.mime_type,
                created_at: request.created_at,
                content_b64: request.content_b64,
                servers: request.servers,
                document_id: request.document_id,
                previous_event_id: request.previous_event_id,
            };
            match prepare_single_block_write(&request) {
                Ok(plan) => WritePlanResponse {
                    ok: true,
                    plan: serde_json::to_value(plan).ok(),
                    error: None,
                },
                Err(error) => WritePlanResponse {
                    ok: false,
                    plan: None,
                    error: Some(error.to_string()),
                },
            }
        }
        Err(error) => WritePlanResponse {
            ok: false,
            plan: None,
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response)
        .unwrap_or_else(|err| format!("{{\"ok\":false,\"plan\":null,\"error\":\"{}\"}}", err));

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_recoverSingleBlockRead(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<RecoverReadJson>(&request_json) {
        Ok(request) => {
            let request = RecoverReadRequest {
                private_key_hex: request.private_key_hex,
                document_id: request.document_id,
                block_index: request.block_index,
                encrypted_block_b64: request.encrypted_block_b64,
            };
            match recover_single_block_read(&request) {
                Ok(content) => ReadRecoveryResponse {
                    ok: true,
                    content_b64: Some(base64::engine::general_purpose::STANDARD.encode(content)),
                    error: None,
                },
                Err(error) => ReadRecoveryResponse {
                    ok: false,
                    content_b64: None,
                    error: Some(error.to_string()),
                },
            }
        }
        Err(error) => ReadRecoveryResponse {
            ok: false,
            content_b64: None,
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response).unwrap_or_else(|err| {
        format!(
            "{{\"ok\":false,\"content_b64\":null,\"error\":\"{}\"}}",
            err
        )
    });

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_signCustomEvent(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<SignEventJson>(&request_json) {
        Ok(request) => {
            let event = UnsignedEvent {
                created_at: request.created_at,
                kind: request.kind,
                tags: request.tags,
                content: request.content,
            };
            match sign_custom_event(&request.private_key_hex, &event) {
                Ok(signed_event) => SignEventResponse {
                    ok: true,
                    event: serde_json::to_value(signed_event).ok(),
                    error: None,
                },
                Err(error) => SignEventResponse {
                    ok: false,
                    event: None,
                    error: Some(error.to_string()),
                },
            }
        }
        Err(error) => SignEventResponse {
            ok: false,
            event: None,
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response)
        .unwrap_or_else(|err| format!("{{\"ok\":false,\"event\":null,\"error\":\"{}\"}}", err));

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_prepareCommitChainSnapshot(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<PrepareCommitChainRequest>(&request_json) {
        Ok(request) => match prepare_commit_chain_snapshot(&request) {
            Ok(result) => CommitChainResponse {
                ok: true,
                result: serde_json::to_value(result).ok(),
                error: None,
            },
            Err(error) => CommitChainResponse {
                ok: false,
                result: None,
                error: Some(error.to_string()),
            },
        },
        Err(error) => CommitChainResponse {
            ok: false,
            result: None,
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response)
        .unwrap_or_else(|err| format!("{{\"ok\":false,\"result\":null,\"error\":\"{}\"}}", err));

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_resolveCommitChainHead(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<ResolveCommitChainHeadRequest>(&request_json) {
        Ok(request) => match resolve_commit_chain_head(&request) {
            Ok(result) => CommitChainResponse {
                ok: true,
                result: serde_json::to_value(result).ok(),
                error: None,
            },
            Err(error) => CommitChainResponse {
                ok: false,
                result: None,
                error: Some(error.to_string()),
            },
        },
        Err(error) => CommitChainResponse {
            ok: false,
            result: None,
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response)
        .unwrap_or_else(|err| format!("{{\"ok\":false,\"result\":null,\"error\":\"{}\"}}", err));

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_readDirectoryEntries(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<ReadDirectoryEntriesRequest>(&request_json) {
        Ok(request) => match read_directory_entries(&request) {
            Ok(result) => CommitChainResponse {
                ok: true,
                result: serde_json::to_value(result).ok(),
                error: None,
            },
            Err(error) => CommitChainResponse {
                ok: false,
                result: None,
                error: Some(error.to_string()),
            },
        },
        Err(error) => CommitChainResponse {
            ok: false,
            result: None,
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response)
        .unwrap_or_else(|err| format!("{{\"ok\":false,\"result\":null,\"error\":\"{}\"}}", err));

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_signBlossomUploadAuth(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<BlossomUploadAuthJson>(&request_json) {
        Ok(request) => match sign_blossom_upload_auth_event(
            &request.private_key_hex,
            &request.share_id_hex,
            &request.server_url,
            request.size_bytes,
            request.created_at,
            request.expiration,
        ) {
            Ok(signed_event) => SignEventResponse {
                ok: true,
                event: serde_json::to_value(signed_event).ok(),
                error: None,
            },
            Err(error) => SignEventResponse {
                ok: false,
                event: None,
                error: Some(error.to_string()),
            },
        },
        Err(error) => SignEventResponse {
            ok: false,
            event: None,
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response)
        .unwrap_or_else(|err| format!("{{\"ok\":false,\"event\":null,\"error\":\"{}\"}}", err));

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_andotherstuff_garland_NativeBridge_executeBlossomUpload(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    let request_json: String = env
        .get_string(&request_json)
        .map(|value| value.into())
        .unwrap_or_default();

    let response = match serde_json::from_str::<BlossomUploadRequest>(&request_json) {
        Ok(request) => match execute_blossom_upload(&request) {
            Ok(result) => BlossomUploadResponseEnvelope {
                ok: true,
                result: serde_json::to_value(result).ok(),
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
            error: Some(format!("invalid request json: {}", error)),
        },
    };

    let payload = serde_json::to_string(&response)
        .unwrap_or_else(|err| format!("{{\"ok\":false,\"result\":null,\"error\":\"{}\"}}", err));

    env.new_string(payload)
        .expect("JNI should allocate response string")
        .into_raw()
}
