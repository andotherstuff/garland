pub mod crypto;
pub mod identity;
pub mod jni_api;
pub mod mvp_write;
pub mod nostr_event;
pub mod packaging;

#[cfg(test)]
mod tests {
    use base64::Engine as _;
    use pretty_assertions::assert_eq;

    use crate::crypto::{
        decrypt_block, encrypt_block, prepare_replication_upload, BlossomServer, REPLICATION_FACTOR,
    };
    use crate::identity::derive_nostr_identity;
    use crate::mvp_write::{
        prepare_single_block_write, recover_single_block_read, PrepareWriteRequest,
        RecoverReadRequest,
    };
    use crate::nostr_event::{sign_custom_event, UnsignedEvent};
    use crate::packaging::{
        frame_content, unframe_content, BLOCK_SIZE, CONTENT_CAPACITY, FRAME_SIZE,
    };

    #[test]
    fn derives_known_nip06_vector() {
        let mnemonic =
            "leader monkey parrot ring guide accident before fence cannon height naive bean";
        let identity = derive_nostr_identity(mnemonic, "").expect("identity should derive");

        assert_eq!(
            identity.private_key_hex,
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
        );
        assert_eq!(
            identity.nsec,
            "nsec10allq0gjx7fddtzef0ax00mdps9t2kmtrldkyjfs8l5xruwvh2dq0lhhkp"
        );
    }

    #[test]
    fn rejects_invalid_mnemonic_checksum() {
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon";
        let error = derive_nostr_identity(mnemonic, "").expect_err("checksum should fail");
        assert!(error.to_string().contains("mnemonic"));
    }

    #[test]
    fn derives_identity_with_passphrase() {
        let mnemonic =
            "leader monkey parrot ring guide accident before fence cannon height naive bean";
        let identity = derive_nostr_identity(mnemonic, "garland-passphrase")
            .expect("identity should derive with passphrase");

        assert_eq!(identity.private_key_hex.len(), 64);
        assert!(identity.nsec.starts_with("nsec1"));
        assert_ne!(
            identity.private_key_hex,
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
        );
    }

    #[test]
    fn frames_and_unframes_short_content() {
        let content = b"garland";
        let frame = frame_content(content).expect("frame should build");

        assert_eq!(frame.len(), FRAME_SIZE);
        assert_eq!(
            u32::from_be_bytes(frame[..4].try_into().unwrap()),
            content.len() as u32
        );

        let recovered = unframe_content(&frame).expect("frame should decode");
        assert_eq!(recovered, content);
    }

    #[test]
    fn frames_empty_content() {
        let frame = frame_content(&[]).expect("empty content should frame");
        assert_eq!(frame.len(), FRAME_SIZE);
        let recovered = unframe_content(&frame).expect("frame should decode");
        assert_eq!(recovered, b"");
    }

    #[test]
    fn rejects_content_larger_than_effective_block_capacity() {
        let content = vec![7_u8; FRAME_SIZE - 3];
        let error = frame_content(&content).expect_err("oversized content should fail");
        assert!(error.to_string().contains("too large"));
    }

    #[test]
    fn exposes_spec_sizes() {
        assert_eq!(BLOCK_SIZE, 262_144);
        assert_eq!(FRAME_SIZE, 262_100);
    }

    #[test]
    fn encrypts_and_decrypts_a_single_block() {
        let file_key = [9_u8; 32];
        let nonce = [3_u8; 12];
        let content = b"garland encrypted block";

        let encrypted = encrypt_block(&file_key, 0, &nonce, content).expect("block should encrypt");
        assert_eq!(encrypted.len(), BLOCK_SIZE);

        let decrypted = decrypt_block(&file_key, 0, &encrypted).expect("block should decrypt");
        assert_eq!(decrypted, content);
    }

    #[test]
    fn prepares_three_replicated_uploads() {
        let servers = vec![
            BlossomServer::new("https://cdn.nostrcheck.me"),
            BlossomServer::new("https://blossom.nostr.build"),
            BlossomServer::new("https://blossom.yakihonne.com"),
        ];

        let upload = prepare_replication_upload([7_u8; 32], 0, [5_u8; 12], b"mvp upload", &servers)
            .expect("upload should prepare");

        assert_eq!(upload.shares.len(), REPLICATION_FACTOR);
        assert_eq!(upload.share_size, BLOCK_SIZE);
        assert!(upload
            .shares
            .windows(2)
            .all(|pair| pair[0].share_id_hex == pair[1].share_id_hex));
        assert_eq!(upload.shares[0].server_url, "https://cdn.nostrcheck.me");
        assert_eq!(upload.shares[1].server_url, "https://blossom.nostr.build");
        assert_eq!(upload.shares[2].server_url, "https://blossom.yakihonne.com");
    }

    #[test]
    fn signs_custom_nostr_event() {
        let event = UnsignedEvent {
            created_at: 1_701_907_200,
            kind: 24_242,
            tags: vec![vec!["t".into(), "upload".into()]],
            content: "garland upload authorization".into(),
        };

        let signed = sign_custom_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &event,
        )
        .expect("event should sign");

        assert_eq!(signed.id_hex.len(), 64);
        assert_eq!(signed.pubkey_hex.len(), 64);
        assert_eq!(signed.sig_hex.len(), 128);
    }

    #[test]
    fn rejects_custom_event_kind_outside_u16_range() {
        let event = UnsignedEvent {
            created_at: 1_701_907_200,
            kind: u16::MAX as u64 + 1,
            tags: vec![vec!["t".into(), "upload".into()]],
            content: "garland upload authorization".into(),
        };

        let error = sign_custom_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &event,
        )
        .expect_err("event kind outside u16 range should fail");

        assert_eq!(error.to_string(), "event kind is invalid");
    }

    #[test]
    fn rejects_custom_event_with_empty_tag() {
        let event = UnsignedEvent {
            created_at: 1_701_907_200,
            kind: 24_242,
            tags: vec![Vec::new()],
            content: "garland upload authorization".into(),
        };

        let error = sign_custom_event(
            "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
            &event,
        )
        .expect_err("empty tag should fail");

        assert_eq!(error.to_string(), "event tags are invalid");
    }

    #[test]
    fn prepares_single_block_write_contract() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            display_name: "note.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: "bXZwIGZpbGU=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan = prepare_single_block_write(&request).expect("write plan should build");

        assert_eq!(plan.uploads.len(), 3);
        assert_eq!(plan.uploads[0].server_url, "https://cdn.nostrcheck.me");
        assert_eq!(plan.uploads[1].server_url, "https://blossom.nostr.build");
        assert_eq!(plan.uploads[2].server_url, "https://blossom.yakihonne.com");
        assert_eq!(plan.commit_event.kind, 1097);
        assert_eq!(plan.commit_event.tags.len(), 0);
        assert_eq!(plan.commit_event.id_hex.len(), 64);
        assert_eq!(plan.commit_event.sig_hex.len(), 128);
        assert_eq!(plan.manifest.document_id.len(), 64);
    }

    #[test]
    fn recovers_single_block_write_content() {
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            display_name: "note.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: "aGVsbG8=".into(),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan = prepare_single_block_write(&request).expect("write plan should build");
        let recovered = recover_single_block_read(&RecoverReadRequest {
            private_key_hex: request.private_key_hex,
            document_id: plan.manifest.document_id,
            block_index: 0,
            encrypted_block_b64: plan.uploads[0].body_b64.clone(),
        })
        .expect("content should recover");

        assert_eq!(recovered, b"hello");
    }

    #[test]
    fn prepares_multi_block_write_contract() {
        let payload = vec![b'g'; CONTENT_CAPACITY + 17];
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            display_name: "big.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: base64::engine::general_purpose::STANDARD.encode(payload),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan =
            prepare_single_block_write(&request).expect("multi-block write plan should build");

        assert_eq!(plan.manifest.blocks.len(), 2);
        assert_eq!(plan.uploads.len(), 6);
        assert_eq!(plan.manifest.blocks[0].index, 0);
        assert_eq!(plan.manifest.blocks[1].index, 1);
    }

    #[test]
    fn recovers_multi_block_write_content() {
        let payload = vec![b'z'; CONTENT_CAPACITY + 17];
        let request = PrepareWriteRequest {
            private_key_hex: "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
                .into(),
            display_name: "big.txt".into(),
            mime_type: "text/plain".into(),
            created_at: 1_701_907_200,
            content_b64: base64::engine::general_purpose::STANDARD.encode(&payload),
            servers: vec![
                "https://cdn.nostrcheck.me".into(),
                "https://blossom.nostr.build".into(),
                "https://blossom.yakihonne.com".into(),
            ],
        };

        let plan =
            prepare_single_block_write(&request).expect("multi-block write plan should build");
        let recovered = plan
            .manifest
            .blocks
            .iter()
            .map(|block| {
                let encrypted = plan
                    .uploads
                    .iter()
                    .find(|upload| upload.share_id_hex == block.share_id_hex)
                    .expect("upload should exist");
                recover_single_block_read(&RecoverReadRequest {
                    private_key_hex: request.private_key_hex.clone(),
                    document_id: plan.manifest.document_id.clone(),
                    block_index: block.index,
                    encrypted_block_b64: encrypted.body_b64.clone(),
                })
                .expect("block should recover")
            })
            .flatten()
            .collect::<Vec<_>>();

        assert_eq!(recovered, payload);
    }
}
