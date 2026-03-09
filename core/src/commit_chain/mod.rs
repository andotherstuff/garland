use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::mvp_write::UploadInstruction;
use crate::nostr_event::SignedEvent;

mod crypto;
mod directory_readback;
mod head_resolution;
mod snapshot_building;

pub use directory_readback::read_directory_entries;
pub use head_resolution::resolve_commit_chain_head;
pub use snapshot_building::prepare_commit_chain_snapshot;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ErasureConfig {
    pub algorithm: String,
    pub k: u8,
    pub n: u8,
    pub field: String,
}

impl ErasureConfig {
    pub(super) fn replicated() -> Self {
        Self {
            algorithm: "reed-solomon".into(),
            k: 1,
            n: 3,
            field: "gf256".into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ShareReference {
    pub id: String,
    pub server: String,
    pub auth: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SingleInodeReference {
    pub format: String,
    pub hash: String,
    pub erasure: ErasureConfig,
    pub shares: Vec<ShareReference>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DirectoryEntry {
    #[serde(rename = "type")]
    pub entry_type: String,
    pub r#ref: SingleInodeReference,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DirectoryBlob {
    pub version: u32,
    #[serde(rename = "type")]
    pub blob_type: String,
    pub created: u64,
    pub modified: u64,
    pub entries: BTreeMap<String, DirectoryEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CommitPayload {
    pub prev: Option<String>,
    pub seq: u64,
    pub root_inode: SingleInodeReference,
    pub garbage: Vec<ShareReference>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DecryptedCommit {
    pub event_id: String,
    pub pubkey_hex: String,
    pub created_at: u64,
    pub payload: CommitPayload,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PrepareCommitChainRequest {
    pub private_key_hex: String,
    pub passphrase: String,
    pub created_at: u64,
    pub prev_event_id: Option<String>,
    pub prev_seq: Option<u64>,
    pub servers: Vec<String>,
    pub entry_names: Vec<String>,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PreparedCommitChainSnapshot {
    pub root_directory: DirectoryBlob,
    pub root_inode: SingleInodeReference,
    pub uploads: Vec<UploadInstruction>,
    pub commit_event: SignedEvent,
    pub payload: CommitPayload,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ResolveCommitChainHeadRequest {
    pub private_key_hex: String,
    pub passphrase: String,
    pub events: Vec<SignedEvent>,
    pub trusted_event_id: Option<String>,
    pub trusted_seq: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ResolveCommitChainHeadResponse {
    pub head: DecryptedCommit,
    pub valid_event_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ReadDirectoryEntriesRequest {
    pub private_key_hex: String,
    pub passphrase: String,
    pub root_inode: SingleInodeReference,
    pub uploads: Vec<UploadInstruction>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ReadDirectoryEntriesResponse {
    pub entries: Vec<String>,
    pub directory: DirectoryBlob,
}

#[derive(Debug, Error)]
pub enum CommitChainError {
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("commit chain requires exactly three servers in MVP mode")]
    InvalidServerCount,
    #[error("previous sequence is required when previous event ID is set")]
    MissingPreviousSequence,
    #[error("previous event ID is required when previous sequence is set")]
    MissingPreviousEventId,
    #[error("directory serialization failed")]
    DirectorySerialization,
    #[error("commit payload serialization failed")]
    CommitSerialization,
    #[error("encryption failed")]
    EncryptionFailed,
    #[error("decryption failed")]
    DecryptionFailed,
    #[error("event signing failed: {0}")]
    EventSigning(String),
    #[error("no valid commit head found")]
    NoValidHead,
    #[error("commit chain is forked")]
    ForkDetected,
    #[error("candidate head is stale")]
    StaleHead,
    #[error("directory share is missing")]
    MissingDirectoryShare,
    #[error("directory share does not match root reference")]
    DirectoryShareMismatch,
}
