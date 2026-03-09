use std::collections::BTreeMap;

use base64::{engine::general_purpose::STANDARD, Engine as _};
use sha2::{Digest, Sha256};

use super::crypto::{
    decode_private_key, derive_branch_key, derive_master_key, encrypt_commit_payload,
    encrypt_metadata_blob,
};
use super::{
    CommitChainError, CommitPayload, DirectoryBlob, DirectoryEntry, ErasureConfig,
    PrepareCommitChainRequest, PreparedCommitChainSnapshot, ShareReference, SingleInodeReference,
};
use crate::mvp_write::UploadInstruction;
use crate::nostr_event::{sign_custom_event, UnsignedEvent};

pub fn prepare_commit_chain_snapshot(
    request: &PrepareCommitChainRequest,
) -> Result<PreparedCommitChainSnapshot, CommitChainError> {
    validate_request(request)?;

    let private_key = decode_private_key(&request.private_key_hex)?;
    let master_key = derive_master_key(&private_key, &request.passphrase)?;
    let metadata_key = derive_branch_key(&master_key, b"garland-v1:metadata")?;
    let commit_key = derive_branch_key(&master_key, b"garland-v1:commit")?;

    let root_directory =
        build_root_directory(request.created_at, &request.entry_names, &request.servers);
    let directory_json = serde_json::to_vec(&root_directory)
        .map_err(|_| CommitChainError::DirectorySerialization)?;
    let encrypted_directory = encrypt_metadata_blob(&metadata_key, &directory_json)?;
    let root_inode = build_single_inode_reference(&encrypted_directory, &request.servers);
    let uploads = build_uploads(&encrypted_directory, &request.servers);
    let payload = build_commit_payload(request, root_inode.clone());
    let commit_content = encrypt_commit_payload(&commit_key, &payload)?;
    let commit_event = sign_custom_event(
        &request.private_key_hex,
        &UnsignedEvent {
            created_at: request.created_at,
            kind: 1097,
            tags: vec![],
            content: commit_content,
        },
    )
    .map_err(|err| CommitChainError::EventSigning(err.to_string()))?;

    Ok(PreparedCommitChainSnapshot {
        root_directory,
        root_inode,
        uploads,
        commit_event,
        payload,
    })
}

fn validate_request(request: &PrepareCommitChainRequest) -> Result<(), CommitChainError> {
    if request.servers.len() != 3 {
        return Err(CommitChainError::InvalidServerCount);
    }
    if request.prev_event_id.is_some() && request.prev_seq.is_none() {
        return Err(CommitChainError::MissingPreviousSequence);
    }
    if request.prev_event_id.is_none() && request.prev_seq.is_some() {
        return Err(CommitChainError::MissingPreviousEventId);
    }
    Ok(())
}

fn build_commit_payload(
    request: &PrepareCommitChainRequest,
    root_inode: SingleInodeReference,
) -> CommitPayload {
    CommitPayload {
        prev: request.prev_event_id.clone(),
        seq: request.prev_seq.map_or(0, |seq| seq + 1),
        root_inode,
        garbage: Vec::new(),
        message: request.message.clone(),
    }
}

fn build_root_directory(
    created_at: u64,
    entry_names: &[String],
    servers: &[String],
) -> DirectoryBlob {
    let entries = entry_names
        .iter()
        .map(|entry_name| {
            (
                entry_name.clone(),
                DirectoryEntry {
                    entry_type: "file".into(),
                    r#ref: placeholder_file_reference(entry_name, servers),
                },
            )
        })
        .collect::<BTreeMap<_, _>>();
    DirectoryBlob {
        version: 1,
        blob_type: "directory".into(),
        created: created_at,
        modified: created_at,
        entries,
    }
}

fn placeholder_file_reference(entry_name: &str, servers: &[String]) -> SingleInodeReference {
    let mut hasher = Sha256::new();
    hasher.update(b"garland-v1:entry:");
    hasher.update(entry_name.as_bytes());
    let hash = hex::encode(hasher.finalize());
    let shares = servers
        .iter()
        .map(|server| ShareReference {
            id: hex::encode(Sha256::digest(
                format!("{}\n{}", entry_name, server).as_bytes(),
            )),
            server: server.clone(),
            auth: "blob".into(),
        })
        .collect();
    SingleInodeReference {
        format: "single".into(),
        hash,
        erasure: ErasureConfig::replicated(),
        shares,
    }
}

fn build_single_inode_reference(body: &[u8], servers: &[String]) -> SingleInodeReference {
    let hash = hex::encode(Sha256::digest(body));
    let shares = servers
        .iter()
        .map(|server| ShareReference {
            id: hash.clone(),
            server: server.clone(),
            auth: "blob".into(),
        })
        .collect();
    SingleInodeReference {
        format: "single".into(),
        hash,
        erasure: ErasureConfig::replicated(),
        shares,
    }
}

fn build_uploads(body: &[u8], servers: &[String]) -> Vec<UploadInstruction> {
    let body_b64 = STANDARD.encode(body);
    let share_id_hex = hex::encode(Sha256::digest(body));
    servers
        .iter()
        .map(|server| UploadInstruction {
            server_url: server.clone(),
            share_id_hex: share_id_hex.clone(),
            body_b64: body_b64.clone(),
        })
        .collect()
}
