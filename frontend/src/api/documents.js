const BASE = '/api';

/**
 * Upload a document into the knowledge base (parse -> split -> embed -> index).
 */
export async function uploadDocument(file) {
  const formData = new FormData();
  formData.append('file', file);
  const res = await fetch(`${BASE}/documents/upload`, {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`上传失败: HTTP ${res.status} ${text}`);
  }
  return res.json();
}

/**
 * List knowledge-base documents.
 */
export async function fetchDocuments() {
  const res = await fetch(`${BASE}/documents`);
  return res.json();
}

/**
 * Delete a document (ES vectors + disk file + registry row).
 */
export async function deleteDocument(id) {
  const res = await fetch(`${BASE}/documents/${id}`, { method: 'DELETE' });
  if (!res.ok) {
    throw new Error(`删除失败: HTTP ${res.status}`);
  }
}
