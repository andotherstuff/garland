use base64::Engine as _;
use nostr::{Keys, SecretKey, Timestamp, Url};
use nostr_blossom::prelude::{BlossomAuthorizationOptions, BlossomClient};
use nostr_blossom::prelude::Error as BlossomClientError;
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::key_hierarchy::{derive_blob_auth_private_key, derive_master_key};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BlossomUploadRequest {
    pub server_url: String,
    pub share_id_hex: String,
    pub body_b64: String,
    pub content_type: String,
    pub private_key_hex: Option<String>,
    pub created_at: Option<u64>,
    pub expiration: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BlossomUploadResponse {
    pub status_code: u16,
    pub response_body: String,
    pub rejection_reason: Option<String>,
    pub retry_after: Option<String>,
}

#[derive(Debug, Error)]
pub enum BlossomUploadError {
    #[error("upload body base64 is invalid")]
    InvalidBodyBase64,
    #[error("upload request URL is invalid")]
    InvalidRequestUrl,
    #[error("private key hex is invalid")]
    InvalidPrivateKey,
    #[error("share ID hex is invalid")]
    InvalidShareId,
    #[error("upload request failed: {0}")]
    RequestFailed(String),
}

pub fn execute_blossom_upload(
    request: &BlossomUploadRequest,
) -> Result<BlossomUploadResponse, BlossomUploadError> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| BlossomUploadError::RequestFailed(error.to_string()))?;

    runtime.block_on(execute_blossom_upload_async(request))
}

async fn execute_blossom_upload_async(
    request: &BlossomUploadRequest,
) -> Result<BlossomUploadResponse, BlossomUploadError> {
    let server_url = Url::parse(&request.server_url).map_err(|_| BlossomUploadError::InvalidRequestUrl)?;
    let body = base64::engine::general_purpose::STANDARD
        .decode(&request.body_b64)
        .map_err(|_| BlossomUploadError::InvalidBodyBase64)?;
    let client = BlossomClient::new(server_url);
    let content_type = Some(request.content_type.clone());

    let signer = match request.private_key_hex.as_deref() {
        Some(private_key_hex) => Some(derive_upload_keys(private_key_hex, &request.share_id_hex)?),
        None => None,
    };
    let authorization_options = signer.as_ref().map(|_| BlossomAuthorizationOptions {
        content: Some("garland upload authorization".to_string()),
        expiration: request.expiration.map(Timestamp::from_secs),
        action: None,
        scope: None,
    });

    match client
        .upload_blob(body, content_type, authorization_options, signer.as_ref())
        .await
    {
        Ok(descriptor) => Ok(BlossomUploadResponse {
            status_code: 200,
            response_body: serde_json::to_string(&descriptor)
                .map_err(|error| BlossomUploadError::RequestFailed(error.to_string()))?,
            rejection_reason: None,
            retry_after: None,
        }),
        Err(BlossomClientError::Response { res, .. }) => {
            let status_code = res.status().as_u16();
            let rejection_reason = res
                .headers()
                .get("X-Reason")
                .and_then(|value| value.to_str().ok())
                .map(str::to_owned);
            let retry_after = res
                .headers()
                .get("Retry-After")
                .and_then(|value| value.to_str().ok())
                .map(str::to_owned);
            let response_body = res
                .text()
                .await
                .map_err(|error| BlossomUploadError::RequestFailed(error.to_string()))?;

            Ok(BlossomUploadResponse {
                status_code,
                response_body,
                rejection_reason,
                retry_after,
            })
        }
        Err(error) => Err(BlossomUploadError::RequestFailed(error.to_string())),
    }
}

fn derive_upload_keys(
    private_key_hex: &str,
    share_id_hex: &str,
) -> Result<Keys, BlossomUploadError> {
    let private_key_bytes =
        hex::decode(private_key_hex).map_err(|_| BlossomUploadError::InvalidPrivateKey)?;
    let private_key_bytes: [u8; 32] = private_key_bytes
        .try_into()
        .map_err(|_| BlossomUploadError::InvalidPrivateKey)?;
    let share_id = hex::decode(share_id_hex).map_err(|_| BlossomUploadError::InvalidShareId)?;
    let share_id: [u8; 32] = share_id
        .try_into()
        .map_err(|_| BlossomUploadError::InvalidShareId)?;
    let master_key =
        derive_master_key(&private_key_bytes).map_err(|_| BlossomUploadError::RequestFailed("failed to derive master key".into()))?;
    let blob_auth_private_key = derive_blob_auth_private_key(&master_key, &share_id)
        .map_err(|_| BlossomUploadError::RequestFailed("failed to derive blob auth key".into()))?;
    let secret_key = SecretKey::from_slice(&blob_auth_private_key)
        .map_err(|_| BlossomUploadError::InvalidPrivateKey)?;
    Ok(Keys::new(secret_key))
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::thread;

    use serde_json::Value;

    use super::*;
    use crate::nostr_event::sign_blossom_upload_auth_event;

    const TEST_PRIVATE_KEY_HEX: &str =
        "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a";

    #[test]
    fn uploads_share_with_blossom_auth_header() {
        let share_id_hex = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        let (server_url, request_rx) = spawn_test_server(
            200,
            vec![],
            format!(
                "{{\"url\":\"http://127.0.0.1/blob/{share_id_hex}\",\"sha256\":\"{share_id_hex}\",\"size\":5,\"type\":\"application/octet-stream\",\"uploaded\":1701907200}}"
            ),
        );

        let response = execute_blossom_upload(&BlossomUploadRequest {
            server_url: server_url.clone(),
            share_id_hex: share_id_hex.into(),
            body_b64: base64::engine::general_purpose::STANDARD.encode(b"hello"),
            content_type: "application/octet-stream".into(),
            private_key_hex: Some(TEST_PRIVATE_KEY_HEX.into()),
            created_at: Some(1_701_907_200),
            expiration: Some(1_701_907_500),
        })
        .expect("upload should succeed");

        assert_eq!(response.status_code, 200);
        assert!(response.response_body.contains("\"sha256\""));

        let captured = request_rx.recv().expect("request should capture");
        assert_eq!(captured.method, "PUT");
        assert_eq!(captured.path, "/upload");
        assert_eq!(
            captured.headers.get("content-type"),
            Some(&"application/octet-stream".to_string())
        );
        assert_eq!(captured.body, b"hello");

        let auth_header = captured
            .headers
            .get("authorization")
            .expect("authorization header should exist");
        assert!(auth_header.starts_with("Nostr "));

        let encoded = auth_header.trim_start_matches("Nostr ");
        let auth_json = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .expect("auth payload should decode");
        let auth_event: Value =
            serde_json::from_slice(&auth_json).expect("auth payload should parse");
        assert_eq!(auth_event.get("kind").and_then(Value::as_u64), Some(24_242));
        let tags = auth_event
            .get("tags")
            .and_then(Value::as_array)
            .expect("tags should exist");
        assert!(tags
            .iter()
            .any(|tag| tag == &serde_json::json!(["t", "upload"])));
        assert!(tags
            .iter()
            .any(|tag| tag == &serde_json::json!(["x", share_id_hex])));
        assert!(tags
            .iter()
            .any(|tag| tag == &serde_json::json!(["expiration", "1701907500"])));
        let expected = sign_blossom_upload_auth_event(
            TEST_PRIVATE_KEY_HEX,
            share_id_hex,
            &server_url,
            5,
            1_701_907_200,
            1_701_907_500,
        )
        .expect("expected auth event should sign");
        assert_eq!(
            auth_event.get("pubkey").and_then(Value::as_str),
            Some(expected.pubkey_hex.as_str())
        );
    }

    #[test]
    fn omits_authorization_header_without_private_key() {
        let share_id_hex = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        let (server_url, request_rx) = spawn_test_server(
            200,
            vec![],
            format!(
                "{{\"url\":\"http://127.0.0.1/blob/{share_id_hex}\",\"sha256\":\"{share_id_hex}\",\"size\":5,\"type\":\"application/octet-stream\",\"uploaded\":1701907200}}"
            ),
        );

        let response = execute_blossom_upload(&BlossomUploadRequest {
            server_url,
            share_id_hex: "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into(),
            body_b64: base64::engine::general_purpose::STANDARD.encode(b"hello"),
            content_type: "application/octet-stream".into(),
            private_key_hex: None,
            created_at: None,
            expiration: None,
        })
        .expect("upload should succeed");

        assert_eq!(response.status_code, 200);
        let captured = request_rx.recv().expect("request should capture");
        assert!(!captured.headers.contains_key("authorization"));
    }

    #[test]
    fn returns_rejection_reason_for_http_failure() {
        let (server_url, _request_rx) = spawn_test_server(
            401,
            vec![("X-Reason".to_string(), "missing blossom auth".to_string())],
            "{\"error\":\"missing blossom auth\"}".into(),
        );

        let response = execute_blossom_upload(&BlossomUploadRequest {
            server_url,
            share_id_hex: "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into(),
            body_b64: base64::engine::general_purpose::STANDARD.encode(b"hello"),
            content_type: "application/octet-stream".into(),
            private_key_hex: None,
            created_at: None,
            expiration: None,
        })
        .expect("upload should return failure response");

        assert_eq!(response.status_code, 401);
        assert_eq!(
            response.rejection_reason.as_deref(),
            Some("missing blossom auth")
        );
        assert!(response.response_body.contains("missing blossom auth"));
        assert_eq!(response.retry_after, None);
    }

    #[test]
    fn returns_retry_after_header_for_retryable_failure() {
        let (server_url, _request_rx) = spawn_test_server(
            503,
            vec![("Retry-After".to_string(), "7".to_string())],
            "{\"error\":\"slow down\"}".into(),
        );

        let response = execute_blossom_upload(&BlossomUploadRequest {
            server_url,
            share_id_hex: "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into(),
            body_b64: base64::engine::general_purpose::STANDARD.encode(b"hello"),
            content_type: "application/octet-stream".into(),
            private_key_hex: None,
            created_at: None,
            expiration: None,
        })
        .expect("upload should return retryable failure response");

        assert_eq!(response.status_code, 503);
        assert_eq!(response.retry_after.as_deref(), Some("7"));
    }

    fn spawn_test_server(
        status_code: u16,
        extra_headers: Vec<(String, String)>,
        response_body: String,
    ) -> (String, mpsc::Receiver<CapturedRequest>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener should bind");
        let address = listener
            .local_addr()
            .expect("listener should expose address");
        let (request_tx, request_rx) = mpsc::channel();
        thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("server should accept connection");
            let request = read_request(&mut stream);
            request_tx.send(request).expect("request should send");

            let mut response = format!(
                "HTTP/1.1 {status_code} OK\r\nContent-Length: {}\r\nConnection: close\r\n",
                response_body.len()
            );
            for (name, value) in extra_headers {
                response.push_str(&format!("{name}: {value}\r\n"));
            }
            response.push_str("\r\n");
            response.push_str(&response_body);
            stream
                .write_all(response.as_bytes())
                .expect("response should write");
        });

        (format!("http://{address}"), request_rx)
    }

    fn read_request(stream: &mut std::net::TcpStream) -> CapturedRequest {
        let mut header_bytes = Vec::new();
        let mut buffer = [0_u8; 1];
        while !header_bytes.ends_with(b"\r\n\r\n") {
            stream
                .read_exact(&mut buffer)
                .expect("header byte should read");
            header_bytes.push(buffer[0]);
        }
        let header_text = String::from_utf8(header_bytes).expect("headers should be utf-8");
        let mut lines = header_text.split("\r\n").filter(|line| !line.is_empty());
        let request_line = lines.next().expect("request line should exist");
        let mut request_line_parts = request_line.split_whitespace();
        let method = request_line_parts.next().unwrap_or_default().to_string();
        let path = request_line_parts.next().unwrap_or_default().to_string();
        let headers = lines
            .map(|line| {
                let (name, value) = line.split_once(':').expect("header should contain colon");
                (name.trim().to_ascii_lowercase(), value.trim().to_string())
            })
            .collect::<HashMap<_, _>>();
        let content_length = headers
            .get("content-length")
            .and_then(|value| value.parse::<usize>().ok())
            .unwrap_or(0);
        let mut body = vec![0_u8; content_length];
        stream
            .read_exact(&mut body)
            .expect("request body should read");

        CapturedRequest {
            method,
            path,
            headers,
            body,
        }
    }

    struct CapturedRequest {
        method: String,
        path: String,
        headers: HashMap<String, String>,
        body: Vec<u8>,
    }
}
