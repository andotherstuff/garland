use hkdf::Hkdf;
use nostr::event::{Kind, Tag, UnsignedEvent as NostrUnsignedEvent};
use nostr::{Keys, SecretKey, Timestamp};
use serde::Serialize;
use sha2::Sha256;
use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct UnsignedEvent {
    pub created_at: u64,
    pub kind: u64,
    pub tags: Vec<Vec<String>>,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SignedEvent {
    pub id_hex: String,
    pub pubkey_hex: String,
    pub created_at: u64,
    pub kind: u64,
    pub tags: Vec<Vec<String>>,
    pub content: String,
    pub sig_hex: String,
}

#[derive(Debug, Error)]
pub enum NostrEventError {
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("event kind is invalid")]
    InvalidKind,
    #[error("event tags are invalid")]
    InvalidTags,
    #[error("share ID hex is invalid")]
    InvalidShareId,
    #[error("event signing failed")]
    SigningFailed,
}

pub fn sign_custom_event(
    private_key_hex: &str,
    event: &UnsignedEvent,
) -> Result<SignedEvent, NostrEventError> {
    let secret_key =
        SecretKey::from_hex(private_key_hex).map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let keys = Keys::new(secret_key);
    let kind = u16::try_from(event.kind).map_err(|_| NostrEventError::InvalidKind)?;
    let tags = event
        .tags
        .iter()
        .map(|tag| Tag::parse(tag.clone()).map_err(|_| NostrEventError::InvalidTags))
        .collect::<Result<Vec<_>, _>>()?;
    let signed = NostrUnsignedEvent::new(
        keys.public_key(),
        Timestamp::from_secs(event.created_at),
        Kind::from(kind),
        tags,
        event.content.clone(),
    )
    .sign_with_keys(&keys)
    .map_err(|_| NostrEventError::SigningFailed)?;

    Ok(SignedEvent {
        id_hex: signed.id.to_hex(),
        pubkey_hex: signed.pubkey.to_hex(),
        created_at: signed.created_at.as_secs(),
        kind: u16::from(signed.kind) as u64,
        tags: signed
            .tags
            .into_iter()
            .map(|tag| tag.as_slice().to_vec())
            .collect(),
        content: signed.content,
        sig_hex: signed.sig.to_string(),
    })
}

pub fn sign_blossom_upload_auth_event(
    private_key_hex: &str,
    share_id_hex: &str,
    created_at: u64,
    expiration: u64,
) -> Result<SignedEvent, NostrEventError> {
    let derived_private_key_hex = derive_blob_auth_private_key_hex(private_key_hex, share_id_hex)?;
    let event = UnsignedEvent {
        created_at,
        kind: 24_242,
        tags: vec![
            vec!["t".into(), "upload".into()],
            vec!["x".into(), share_id_hex.to_lowercase()],
            vec!["expiration".into(), expiration.to_string()],
        ],
        content: "garland upload authorization".into(),
    };
    sign_custom_event(&derived_private_key_hex, &event)
}

fn derive_blob_auth_private_key_hex(
    private_key_hex: &str,
    share_id_hex: &str,
) -> Result<String, NostrEventError> {
    let seed_bytes =
        hex::decode(private_key_hex).map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let seed_bytes: [u8; 32] = seed_bytes
        .try_into()
        .map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let share_id = hex::decode(share_id_hex).map_err(|_| NostrEventError::InvalidShareId)?;
    if share_id.len() != 32 {
        return Err(NostrEventError::InvalidShareId);
    }

    let hk = Hkdf::<Sha256>::from_prk(&seed_bytes).map_err(|_| NostrEventError::SigningFailed)?;
    let mut counter = 0_u32;
    loop {
        let mut candidate = [0_u8; 32];
        let mut info = Vec::with_capacity(b"garland-v1:auth:".len() + share_id.len() + 4);
        info.extend_from_slice(b"garland-v1:auth:");
        info.extend_from_slice(&share_id);
        info.extend_from_slice(&counter.to_be_bytes());
        hk.expand(&info, &mut candidate)
            .map_err(|_| NostrEventError::SigningFailed)?;
        if SecretKey::from_slice(&candidate).is_ok() {
            return Ok(hex::encode(candidate));
        }
        counter = counter.wrapping_add(1);
    }
}
