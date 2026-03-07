use k256::elliptic_curve::FieldBytes;
use k256::schnorr::{Signature, SigningKey};
use k256::Secp256k1;
use serde::Serialize;
use sha2::{Digest, Sha256};
use signature::hazmat::PrehashSigner;
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
    #[error("event serialization failed")]
    SerializationFailed,
    #[error("schnorr signing failed")]
    SigningFailed,
}

pub fn sign_custom_event(
    private_key_hex: &str,
    event: &UnsignedEvent,
) -> Result<SignedEvent, NostrEventError> {
    let private_key_vec =
        hex::decode(private_key_hex).map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let private_key_bytes: [u8; 32] = private_key_vec
        .as_slice()
        .try_into()
        .map_err(|_| NostrEventError::InvalidPrivateKey)?;

    let signing_key =
        SigningKey::from_bytes(FieldBytes::<Secp256k1>::from_slice(&private_key_bytes))
            .map_err(|_| NostrEventError::InvalidPrivateKey)?;
    let pubkey_hex = hex::encode(signing_key.verifying_key().to_bytes());

    let event_data = serde_json::json!([
        0,
        pubkey_hex,
        event.created_at,
        event.kind,
        event.tags,
        event.content,
    ]);
    let serialized =
        serde_json::to_vec(&event_data).map_err(|_| NostrEventError::SerializationFailed)?;
    let id = Sha256::digest(&serialized);
    let signature: Signature = signing_key
        .sign_prehash(&id)
        .map_err(|_| NostrEventError::SigningFailed)?;

    Ok(SignedEvent {
        id_hex: hex::encode(id),
        pubkey_hex,
        created_at: event.created_at,
        kind: event.kind,
        tags: event.tags.clone(),
        content: event.content.clone(),
        sig_hex: hex::encode(signature.to_bytes()),
    })
}
