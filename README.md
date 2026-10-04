# FastCloud Storage Lite

Aplikasi Android Kotlin + backend TypeScript Fastly Compute@Edge sesuai PRD, dengan perubahan arsitektur agar upload file langsung ke Object Storage.

## Alur Upload

Upload file, termasuk gambar, tidak dikirim melewati backend.

1. Android memanggil `POST /api/v1/files/upload-url` dengan nama, ukuran, dan MIME type.
2. Backend membuat presigned `PUT` URL ke S3-compatible Object Storage.
3. Android melakukan `PUT` langsung ke `upload_url` memakai OkHttp.
4. Backend hanya dipakai untuk list, presigned download URL, dan delete.

Endpoint backend sengaja tidak menyediakan `PUT /api/v1/files/upload` agar file binary tidak diproxy melalui backend.

## Konfigurasi Android

Atur base URL saat build:

```powershell
.\gradlew.bat assembleDebug -PFASTCLOUD_API_BASE_URL=https://your-fastly-compute-domain.edgecompute.app/
```

## Konfigurasi Backend

Backend ada di folder `backend/` dan dibangun dengan Node.js:

```powershell
cd backend
npm install
npm run typecheck
npm run build
```

Simpan kredensial storage sebagai secret/environment di Fastly, bukan di source code:

- `S3_BUCKET`
- `S3_REGION`
- `S3_ENDPOINT`
- `S3_ACCESS_KEY_ID`
- `S3_SECRET_ACCESS_KEY`
- `S3_PUBLIC_BASE_URL` opsional untuk preview gambar publik

Object storage harus mengizinkan CORS `PUT` dari aplikasi atau origin yang dipakai saat testing.
