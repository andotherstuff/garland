use std::collections::{BTreeMap, HashMap, HashSet};

use base64::{engine::general_purpose::STANDARD, Engine as _};
use chacha20::cipher::{KeyIvInit, StreamCipher};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::mvp_write::UploadInstruction;
use crate::nostr_event::{sign_custom_event, SignedEvent, UnsignedEvent};
use crate::packaging::{frame_content, unframe_content, BLOCK_SIZE};

type HmacSha256 = Hmac<Sha256>;
type ChaCha20Cipher = chacha20::ChaCha20;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ErasureConfig {
    pub algorithm: String,
    pub k: u8,
    pub n: u8,
    pub field: String,
}

impl ErasureConfig {
    fn replicated() -> Self {
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

pub fn prepare_commit_chain_snapshot(
    request: &PrepareCommitChainRequest,
) -> Result<PreparedCommitChainSnapshot, CommitChainError> {
    if request.servers.len() != 3 {
        return Err(CommitChainError::InvalidServerCount);
    }
    if request.prev_event_id.is_some() && request.prev_seq.is_none() {
        return Err(CommitChainError::MissingPreviousSequence);
    }
    if request.prev_event_id.is_none() && request.prev_seq.is_some() {
        return Err(CommitChainError::MissingPreviousEventId);
    }

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
    let payload = CommitPayload {
        prev: request.prev_event_id.clone(),
        seq: request.prev_seq.map_or(0, |seq| seq + 1),
        root_inode: root_inode.clone(),
        garbage: Vec::new(),
        message: request.message.clone(),
    };
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

pub fn resolve_commit_chain_head(
    request: &ResolveCommitChainHeadRequest,
) -> Result<ResolveCommitChainHeadResponse, CommitChainError> {
    let private_key = decode_private_key(&request.private_key_hex)?;
    let master_key = derive_master_key(&private_key, &request.passphrase)?;
    let commit_key = derive_branch_key(&master_key, b"garland-v1:commit")?;
    let mut decrypted = HashMap::new();

    for event in &request.events {
        if event.kind != 1097 || !event.tags.is_empty() {
            continue;
        }
        let payload = match decrypt_commit_payload(&commit_key, &event.content) {
            Ok(payload) => payload,
            Err(_) => continue,
        };
        decrypted.insert(
            event.id_hex.clone(),
            DecryptedCommit {
                event_id: event.id_hex.clone(),
                pubkey_hex: event.pubkey_hex.clone(),
                created_at: event.created_at,
                payload,
            },
        );
    }

    let mut memo = HashMap::new();
    let mut visiting = HashSet::new();
    let valid_ids: Vec<String> = decrypted
        .keys()
        .filter(|event_id| is_valid_commit(event_id, &decrypted, &mut memo, &mut visiting))
        .cloned()
        .collect();

    if valid_ids.is_empty() {
        return Err(CommitChainError::NoValidHead);
    }

    let valid_set: HashSet<String> = valid_ids.iter().cloned().collect();
    let referenced: HashSet<String> = valid_ids
        .iter()
        .filter_map(|event_id| decrypted[event_id].payload.prev.clone())
        .filter(|event_id| valid_set.contains(event_id))
        .collect();
    let tips: Vec<&DecryptedCommit> = valid_ids
        .iter()
        .filter(|event_id| !referenced.contains(*event_id))
        .map(|event_id| &decrypted[event_id])
        .collect();

    let max_seq = tips
        .iter()
        .map(|commit| commit.payload.seq)
        .max()
        .ok_or(CommitChainError::NoValidHead)?;
    let best_tips: Vec<&DecryptedCommit> = tips
        .into_iter()
        .filter(|commit| commit.payload.seq == max_seq)
        .collect();

    if best_tips.len() != 1 {
        return Err(CommitChainError::ForkDetected);
    }

    let head = best_tips[0].clone();
    if let Some(trusted_seq) = request.trusted_seq {
        if head.payload.seq < trusted_seq {
            return Err(CommitChainError::StaleHead);
        }
        if head.payload.seq == trusted_seq {
            if let Some(trusted_event_id) = &request.trusted_event_id {
                if &head.event_id != trusted_event_id {
                    return Err(CommitChainError::ForkDetected);
                }
            }
        }
    }

    let mut valid_event_ids = valid_ids;
    valid_event_ids.sort();
    Ok(ResolveCommitChainHeadResponse {
        head,
        valid_event_ids,
    })
}

pub fn read_directory_entries(
    request: &ReadDirectoryEntriesRequest,
) -> Result<ReadDirectoryEntriesResponse, CommitChainError> {
    let private_key = decode_private_key(&request.private_key_hex)?;
    let master_key = derive_master_key(&private_key, &request.passphrase)?;
    let metadata_key = derive_branch_key(&master_key, b"garland-v1:metadata")?;
    let share = request
        .root_inode
        .shares
        .iter()
        .find_map(|share_ref| {
            request
                .uploads
                .iter()
                .find(|upload| upload.share_id_hex == share_ref.id)
        })
        .ok_or(CommitChainError::MissingDirectoryShare)?;
    let encrypted = STANDARD
        .decode(&share.body_b64)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let encrypted_hash = hex::encode(Sha256::digest(&encrypted));
    if encrypted_hash != request.root_inode.hash {
        return Err(CommitChainError::DirectoryShareMismatch);
    }
    let plaintext = decrypt_metadata_blob(&metadata_key, &encrypted)?;
    let directory: DirectoryBlob =
        serde_json::from_slice(&plaintext).map_err(|_| CommitChainError::DecryptionFailed)?;
    Ok(ReadDirectoryEntriesResponse {
        entries: directory.entries.keys().cloned().collect(),
        directory,
    })
}

fn build_root_directory(
    created_at: u64,
    entry_names: &[String],
    servers: &[String],
) -> DirectoryBlob {
    let mut entries = BTreeMap::new();
    for entry_name in entry_names {
        entries.insert(
            entry_name.clone(),
            DirectoryEntry {
                entry_type: "file".into(),
                r#ref: placeholder_file_reference(entry_name, servers),
            },
        );
    }
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

fn decode_private_key(private_key_hex: &str) -> Result<[u8; 32], CommitChainError> {
    let bytes = hex::decode(private_key_hex).map_err(|_| CommitChainError::InvalidPrivateKey)?;
    bytes
        .try_into()
        .map_err(|_| CommitChainError::InvalidPrivateKey)
}

fn derive_master_key(
    private_key_hex: &[u8; 32],
    passphrase: &str,
) -> Result<[u8; 32], CommitChainError> {
    let hk = Hkdf::<Sha256>::new(Some(passphrase.as_bytes()), private_key_hex);
    let mut master_key = [0_u8; 32];
    hk.expand(b"garland-v1:master", &mut master_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    Ok(master_key)
}

fn derive_branch_key(master_key: &[u8; 32], label: &[u8]) -> Result<[u8; 32], CommitChainError> {
    let hk = Hkdf::<Sha256>::new(None, master_key);
    let mut branch_key = [0_u8; 32];
    hk.expand(label, &mut branch_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    Ok(branch_key)
}

fn split_enc_mac_key(key_material: &[u8; 32]) -> Result<([u8; 32], [u8; 32]), CommitChainError> {
    let hk = Hkdf::<Sha256>::new(None, key_material);
    let mut enc_key = [0_u8; 32];
    let mut mac_key = [0_u8; 32];
    hk.expand(b"garland-v1:enc", &mut enc_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    hk.expand(b"garland-v1:mac", &mut mac_key)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    Ok((enc_key, mac_key))
}

fn encrypt_metadata_blob(
    metadata_key: &[u8; 32],
    plaintext: &[u8],
) -> Result<Vec<u8>, CommitChainError> {
    let framed = frame_content(plaintext).map_err(|_| CommitChainError::EncryptionFailed)?;
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let (enc_key, mac_key) = split_enc_mac_key(metadata_key)?;
    let mut ciphertext = framed;
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    cipher.apply_keystream(&mut ciphertext);
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::EncryptionFailed)?;
    mac.update(&nonce);
    mac.update(&ciphertext);
    let tag = mac.finalize().into_bytes();
    let mut encrypted = Vec::with_capacity(BLOCK_SIZE);
    encrypted.extend_from_slice(&nonce);
    encrypted.extend_from_slice(&ciphertext);
    encrypted.extend_from_slice(&tag);
    Ok(encrypted)
}

fn decrypt_metadata_blob(
    metadata_key: &[u8; 32],
    encrypted: &[u8],
) -> Result<Vec<u8>, CommitChainError> {
    if encrypted.len() != BLOCK_SIZE {
        return Err(CommitChainError::DecryptionFailed);
    }
    let nonce: [u8; 12] = encrypted[..12]
        .try_into()
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let ciphertext = &encrypted[12..BLOCK_SIZE - 32];
    let tag = &encrypted[BLOCK_SIZE - 32..];
    let (enc_key, mac_key) = split_enc_mac_key(metadata_key)?;
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::DecryptionFailed)?;
    mac.update(&nonce);
    mac.update(ciphertext);
    mac.verify_slice(tag)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let mut plaintext = ciphertext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    cipher.apply_keystream(&mut plaintext);
    unframe_content(&plaintext).map_err(|_| CommitChainError::DecryptionFailed)
}

fn encrypt_commit_payload(
    commit_key: &[u8; 32],
    payload: &CommitPayload,
) -> Result<String, CommitChainError> {
    let plaintext =
        serde_json::to_vec(payload).map_err(|_| CommitChainError::CommitSerialization)?;
    let mut nonce = [0_u8; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let (enc_key, mac_key) = split_enc_mac_key(commit_key)?;
    let mut ciphertext = plaintext;
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::EncryptionFailed)?;
    cipher.apply_keystream(&mut ciphertext);
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::EncryptionFailed)?;
    mac.update(&nonce);
    mac.update(&ciphertext);
    let tag = mac.finalize().into_bytes();
    let mut encoded = Vec::with_capacity(12 + ciphertext.len() + 32);
    encoded.extend_from_slice(&nonce);
    encoded.extend_from_slice(&ciphertext);
    encoded.extend_from_slice(&tag);
    Ok(STANDARD.encode(encoded))
}

fn decrypt_commit_payload(
    commit_key: &[u8; 32],
    encoded: &str,
) -> Result<CommitPayload, CommitChainError> {
    let payload = STANDARD
        .decode(encoded)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    if payload.len() < 12 + 32 {
        return Err(CommitChainError::DecryptionFailed);
    }
    let nonce: [u8; 12] = payload[..12]
        .try_into()
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let ciphertext = &payload[12..payload.len() - 32];
    let tag = &payload[payload.len() - 32..];
    let (enc_key, mac_key) = split_enc_mac_key(commit_key)?;
    let mut mac =
        HmacSha256::new_from_slice(&mac_key).map_err(|_| CommitChainError::DecryptionFailed)?;
    mac.update(&nonce);
    mac.update(ciphertext);
    mac.verify_slice(tag)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    let mut plaintext = ciphertext.to_vec();
    let mut cipher = ChaCha20Cipher::new_from_slices(&enc_key, &nonce)
        .map_err(|_| CommitChainError::DecryptionFailed)?;
    cipher.apply_keystream(&mut plaintext);
    serde_json::from_slice(&plaintext).map_err(|_| CommitChainError::DecryptionFailed)
}

fn is_valid_commit(
    event_id: &str,
    decrypted: &HashMap<String, DecryptedCommit>,
    memo: &mut HashMap<String, bool>,
    visiting: &mut HashSet<String>,
) -> bool {
    if let Some(result) = memo.get(event_id) {
        return *result;
    }
    if !visiting.insert(event_id.to_owned()) {
        memo.insert(event_id.to_owned(), false);
        return false;
    }
    let Some(commit) = decrypted.get(event_id) else {
        visiting.remove(event_id);
        return false;
    };
    let result = match &commit.payload.prev {
        None => commit.payload.seq == 0,
        Some(parent_id) => {
            if !is_valid_commit(parent_id, decrypted, memo, visiting) {
                false
            } else if let Some(parent) = decrypted.get(parent_id) {
                commit.payload.seq == parent.payload.seq + 1
            } else {
                false
            }
        }
    };
    visiting.remove(event_id);
    memo.insert(event_id.to_owned(), result);
    result
}
