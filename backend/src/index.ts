/// <reference types="@fastly/js-compute" />

import { ConfigStore } from "fastly:config-store";
import { SecretStore } from "fastly:secret-store";
import { CacheOverride } from "fastly:cache-override";

const S3_BACKEND = "s3_origin";
const CONFIG_STORE = "fastcloud_config";
const SECRET_STORE = "fastcloud_secrets";

type ApiResponse<T> = {
  success: boolean;
  data: T | null;
  message: string | null;
};

type UploadUrlRequest = {
  name: string;
  content_type: string;
  size: number;
};

type RenameFileRequest = {
  old_name: string;
  new_name: string;
};

type UploadUrlResponse = {
  object_key: string;
  upload_url: string;
  public_url: string | null;
  expires_at: string;
  headers: Record<string, string>;
};

type FileItem = {
  name: string;
  size: number;
  last_modified: string;
  content_type: string | null;
  download_url: string | null;
};

type Config = {
  bucket: string;
  region: string;
  endpoint: string;
  accessKey: string;
  secretKey: string;
  publicBaseUrl: string | null;
};

addEventListener("fetch", (event: FetchEvent) => {
  event.respondWith(handleRequest(event.request));
});

async function handleRequest(request: Request): Promise<Response> {
  if (request.method === "OPTIONS") {
    return withCors(new Response(null, { status: 204 }));
  }

  const url = new URL(request.url);
  const path = url.pathname;

  try {
    if (request.method === "GET" && path === "/api/v1/files") {
      return withCors(await listFiles());
    }

    if (request.method === "POST" && path === "/api/v1/files/upload-url") {
      return withCors(await createUploadUrl(request));
    }

    if (request.method === "POST" && path === "/api/v1/files/rename") {
      return withCors(await renameFile(request));
    }

    if (request.method === "GET" && path.startsWith("/api/v1/files/")) {
      return withCors(await getFileUrl(path.slice("/api/v1/files/".length)));
    }

    if (request.method === "DELETE" && path.startsWith("/api/v1/files/")) {
      return withCors(await deleteFile(path.slice("/api/v1/files/".length)));
    }

    return withCors(json({ success: false, data: null, message: "Endpoint tidak ditemukan" }, 404));
  } catch (error) {
    return withCors(json({
      success: false,
      data: null,
      message: error instanceof Error ? error.message : "Terjadi kesalahan backend",
    }, 500));
  }
}

async function listFiles(): Promise<Response> {
  const config = await readConfig();
  const path = `/${config.bucket}`;
  const query = `list-type=2&request-id=${Date.now()}`;
  const headers = await signedHeaders(config, "GET", path, "UNSIGNED-PAYLOAD", query);
  const response = await fetch(`${config.endpoint}${path}?${query}`, {
    backend: S3_BACKEND,
    cacheOverride: new CacheOverride("pass"),
    method: "GET",
    headers,
  } as RequestInit);

  if (!response.ok) {
    return json({ success: false, data: null, message: `Gagal mengambil daftar dari Object Storage: HTTP ${response.status}` }, 502);
  }

  const xml = await response.text();
  const files = parseS3List(xml, config);
  await Promise.all(files.map(async (file) => {
    file.download_url = await presignUrl(config, "GET", file.name, 600, null);
  }));
  return json({ success: true, data: files, message: null });
}

async function createUploadUrl(request: Request): Promise<Response> {
  const config = await readConfig();
  const body = await request.json() as Partial<UploadUrlRequest>;
  if (!body.name || !body.content_type || !body.size) {
    return json({ success: false, data: null, message: "Nama, MIME type, dan ukuran file wajib valid" }, 400);
  }

  const key = sanitizeKey(body.name);
  const expires = 600;
  const uploadUrl = await presignUrl(config, "PUT", key, expires, body.content_type);
  const publicUrl = config.publicBaseUrl
    ? `${config.publicBaseUrl.replace(/\/+$/, "")}/${encodePath(key)}`
    : null;

  const data: UploadUrlResponse = {
    object_key: key,
    upload_url: uploadUrl,
    public_url: publicUrl,
    expires_at: new Date(Date.now() + expires * 1000).toISOString(),
    headers: { "Content-Type": body.content_type },
  };

  return json({ success: true, data, message: null });
}

async function getFileUrl(encodedName: string): Promise<Response> {
  const config = await readConfig();
  const key = sanitizeKey(decodeURIComponent(encodedName));
  const downloadUrl = await presignUrl(config, "GET", key, 600, null);
  const data: FileItem = {
    name: key,
    size: 0,
    last_modified: "",
    content_type: guessContentType(key),
    download_url: downloadUrl,
  };
  return json({ success: true, data, message: null });
}

async function renameFile(request: Request): Promise<Response> {
  const config = await readConfig();
  const body = await request.json() as Partial<RenameFileRequest>;
  if (!body.old_name || !body.new_name) {
    return json({ success: false, data: null, message: "Nama lama dan nama baru wajib diisi" }, 400);
  }

  const oldKey = sanitizeKey(body.old_name);
  const newKey = sanitizeKey(body.new_name);
  if (!oldKey || !newKey || oldKey === newKey) {
    return json({ success: false, data: null, message: "Nama file tidak valid" }, 400);
  }

  const path = `/${config.bucket}/${encodePath(newKey)}`;
  const copySource = `/${config.bucket}/${encodePath(oldKey)}`;
  const copyHeaders = await signedHeaders(config, "PUT", path, "UNSIGNED-PAYLOAD", "", {
    "x-amz-copy-source": copySource,
  });
  const copyResponse = await fetch(`${config.endpoint}${path}`, {
    backend: S3_BACKEND,
    method: "PUT",
    headers: copyHeaders,
  } as RequestInit);

  if (!copyResponse.ok) {
    return json({ success: false, data: null, message: `Gagal rename file: copy HTTP ${copyResponse.status}` }, 502);
  }

  const deleteResponse = await deleteObject(config, oldKey);
  return json({
    success: deleteResponse.ok,
    data: null,
    message: deleteResponse.ok ? "File renamed successfully" : `File tersalin, tapi gagal menghapus nama lama: HTTP ${deleteResponse.status}`,
  }, deleteResponse.ok ? 200 : 502);
}

async function deleteFile(encodedName: string): Promise<Response> {
  const config = await readConfig();
  const key = sanitizeKey(decodeURIComponent(encodedName));
  const response = await deleteObject(config, key);

  return json({
    success: response.ok,
    data: null,
    message: response.ok ? "File deleted successfully" : "Gagal menghapus file",
  }, response.ok ? 200 : 502);
}

async function deleteObject(config: Config, key: string): Promise<Response> {
  const path = `/${config.bucket}/${encodePath(key)}`;
  const headers = await signedHeaders(config, "DELETE", path, "UNSIGNED-PAYLOAD");
  return fetch(`${config.endpoint}${path}`, {
    backend: S3_BACKEND,
    method: "DELETE",
    headers,
  } as RequestInit);
}

async function presignUrl(config: Config, method: string, key: string, expires: number, contentType: string | null): Promise<string> {
  const now = new Date();
  const date = yyyymmdd(now);
  const amzDate = amzDateTime(now);
  const host = hostFromEndpoint(config.endpoint);
  const credentialScope = `${date}/${config.region}/s3/aws4_request`;
  const credential = `${config.accessKey}/${credentialScope}`;
  const path = `/${config.bucket}/${encodePath(key)}`;
  const signedHeaders = contentType ? "content-type;host" : "host";
  const query = new URLSearchParams({
    "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
    "X-Amz-Credential": credential,
    "X-Amz-Date": amzDate,
    "X-Amz-Expires": String(expires),
    "X-Amz-SignedHeaders": signedHeaders,
  });
  query.sort();

  const canonicalHeaders = contentType
    ? `content-type:${contentType}\nhost:${host}\n`
    : `host:${host}\n`;
  const canonicalRequest = [
    method,
    path,
    query.toString(),
    canonicalHeaders,
    signedHeaders,
    "UNSIGNED-PAYLOAD",
  ].join("\n");
  const stringToSign = [
    "AWS4-HMAC-SHA256",
    amzDate,
    credentialScope,
    await sha256Hex(canonicalRequest),
  ].join("\n");
  const signature = await hmacHex(await signingKey(config.secretKey, date, config.region), stringToSign);
  return `${config.endpoint}${path}?${query.toString()}&X-Amz-Signature=${signature}`;
}

async function signedHeaders(
  config: Config,
  method: string,
  path: string,
  payloadHash: string,
  canonicalQuery = "",
  extraHeaders: Record<string, string> = {},
): Promise<Headers> {
  const now = new Date();
  const date = yyyymmdd(now);
  const amzDate = amzDateTime(now);
  const host = hostFromEndpoint(config.endpoint);
  const headerValues: Record<string, string> = {
    host,
    "x-amz-content-sha256": payloadHash,
    "x-amz-date": amzDate,
    ...normalizeHeaderNames(extraHeaders),
  };
  const sortedNames = Object.keys(headerValues).sort();
  const signedHeadersValue = sortedNames.join(";");
  const canonicalHeaders = sortedNames.map((name) => `${name}:${headerValues[name].trim()}\n`).join("");
  const canonicalRequest = [method, path, canonicalQuery, canonicalHeaders, signedHeadersValue, payloadHash].join("\n");
  const scope = `${date}/${config.region}/s3/aws4_request`;
  const stringToSign = ["AWS4-HMAC-SHA256", amzDate, scope, await sha256Hex(canonicalRequest)].join("\n");
  const signature = await hmacHex(await signingKey(config.secretKey, date, config.region), stringToSign);

  const headers = new Headers(headerValues);
  headers.set("authorization", `AWS4-HMAC-SHA256 Credential=${config.accessKey}/${scope}, SignedHeaders=${signedHeadersValue}, Signature=${signature}`);
  return headers;
}

function normalizeHeaderNames(headers: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]));
}

async function signingKey(secret: string, date: string, region: string): Promise<ArrayBuffer> {
  const dateKey = await hmacRaw(utf8(`AWS4${secret}`), date);
  const regionKey = await hmacRaw(dateKey, region);
  const serviceKey = await hmacRaw(regionKey, "s3");
  return hmacRaw(serviceKey, "aws4_request");
}

async function hmacRaw(key: BufferSource, data: string): Promise<ArrayBuffer> {
  const cryptoKey = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return crypto.subtle.sign("HMAC", cryptoKey, utf8(data));
}

async function hmacHex(key: BufferSource, data: string): Promise<string> {
  return hex(await hmacRaw(key, data));
}

async function sha256Hex(data: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", utf8(data)));
}

function parseS3List(xml: string, config: Config): FileItem[] {
  const items: FileItem[] = [];
  const contents = xml.matchAll(/<Contents>([\s\S]*?)<\/Contents>/g);
  for (const item of contents) {
    const key = readXmlText(item[1], "Key");
    if (!key) continue;
    const size = Number(readXmlText(item[1], "Size") || 0);
    const lastModified = readXmlText(item[1], "LastModified") || "";
    items.push({
      name: key,
      size,
      last_modified: lastModified,
      content_type: guessContentType(key),
      download_url: config.publicBaseUrl ? `${config.publicBaseUrl.replace(/\/+$/, "")}/${encodePath(key)}` : null,
    });
  }
  return items;
}

function readXmlText(xml: string, tag: string): string | null {
  const match = xml.match(new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`));
  return match ? decodeXml(match[1]) : null;
}

async function readConfig(): Promise<Config> {
  const config = new ConfigStore(CONFIG_STORE);
  const secrets = new SecretStore(SECRET_STORE);
  const accessKey = await readSecret(secrets, "S3_ACCESS_KEY_ID");
  const secretKey = await readSecret(secrets, "S3_SECRET_ACCESS_KEY");

  return {
    bucket: requireConfig(config, "S3_BUCKET"),
    region: config.get("S3_REGION") || "us-east-1",
    endpoint: requireConfig(config, "S3_ENDPOINT").replace(/\/+$/, ""),
    accessKey,
    secretKey,
    publicBaseUrl: config.get("S3_PUBLIC_BASE_URL") || null,
  };
}

function requireConfig(store: ConfigStore, name: string): string {
  const value = store.get(name);
  if (!value) throw new Error(`${name} belum dikonfigurasi di Config Store ${CONFIG_STORE}`);
  return value;
}

async function readSecret(store: SecretStore, name: string): Promise<string> {
  const entry = await store.get(name);
  if (!entry) throw new Error(`${name} belum dikonfigurasi di Secret Store ${SECRET_STORE}`);
  return entry.plaintext();
}

function json<T>(body: ApiResponse<T>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function withCors(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("access-control-allow-origin", "*");
  headers.set("access-control-allow-methods", "GET, POST, DELETE, OPTIONS");
  headers.set("access-control-allow-headers", "content-type, authorization");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function sanitizeKey(name: string): string {
  return name.replaceAll("\\", "/")
    .split("/")
    .filter((part) => part && part !== "." && part !== "..")
    .join("/");
}

function encodePath(value: string): string {
  return value.split("/").map(encodeURIComponent).join("/");
}

function yyyymmdd(date: Date): string {
  return date.toISOString().slice(0, 10).replaceAll("-", "");
}

function amzDateTime(date: Date): string {
  return date.toISOString().replace(/[:-]|\.\d{3}/g, "");
}

function hostFromEndpoint(endpoint: string): string {
  return new URL(endpoint).host;
}

function guessContentType(name: string): string | null {
  const ext = name.split(".").pop()?.toLowerCase();
  if (ext === "jpg" || ext === "jpeg") return "image/jpeg";
  if (ext === "png") return "image/png";
  if (ext === "webp") return "image/webp";
  if (ext === "gif") return "image/gif";
  if (ext === "pdf") return "application/pdf";
  if (ext === "txt") return "text/plain";
  if (ext === "json") return "application/json";
  if (ext === "csv") return "text/csv";
  if (ext === "md") return "text/markdown";
  if (ext === "mp4") return "video/mp4";
  if (ext === "mp3") return "audio/mpeg";
  if (ext === "doc" || ext === "docx") return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  if (ext === "xls" || ext === "xlsx") return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  if (ext === "zip") return "application/zip";
  return null;
}

function decodeXml(value: string): string {
  return value
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", "\"")
    .replaceAll("&apos;", "'")
    .replaceAll("&amp;", "&");
}

function utf8(value: string): ArrayBuffer {
  const bytes = new TextEncoder().encode(value);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

function hex(buffer: ArrayBuffer): string {
  return [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
