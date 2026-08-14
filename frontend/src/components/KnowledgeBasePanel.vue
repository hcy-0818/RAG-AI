<template>
  <div class="kb-panel">
    <div class="kb-header">📚 知识库</div>

    <div class="upload-area">
      <input
        type="file"
        ref="fileInput"
        accept=".pdf,.docx,.txt,.md"
        hidden
        @change="onFileChange"
      />
      <button class="upload-btn" :disabled="uploading" @click="fileInput.click()">
        {{ uploading ? '⏳ 解析入库中...' : '⬆ 上传文档' }}
      </button>
      <p class="hint">支持 PDF / Word / TXT / Markdown，上传后自动解析、切分、向量化入库</p>
    </div>

    <div class="doc-list">
      <div v-for="doc in documents" :key="doc.id" class="doc-item">
        <div class="doc-info">
          <span class="doc-name">{{ doc.fileName }}</span>
          <span class="doc-meta">
            {{ formatSize(doc.fileSize) }} · {{ doc.chunkCount }} 块
            <span :class="['status', doc.status.toLowerCase()]">{{ doc.status }}</span>
          </span>
          <span v-if="doc.errorMsg" class="doc-error">{{ doc.errorMsg }}</span>
        </div>
        <button class="doc-delete" title="删除文档" @click="$emit('delete', doc)">×</button>
      </div>

      <div v-if="documents.length === 0 && !uploading" class="empty-hint">
        暂无文档，上传文档后即可针对其内容提问
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  documents: { type: Array, default: () => [] },
  uploading: { type: Boolean, default: false },
})

const emit = defineEmits(['upload', 'delete'])

const fileInput = ref(null)

function onFileChange(event) {
  const file = event.target.files[0]
  event.target.value = '' // allow re-uploading the same file
  if (file) {
    emit('upload', file)
  }
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}
</script>

<style scoped>
.kb-panel {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
}

.kb-header {
  padding: 12px;
  font-size: 15px;
  font-weight: 600;
}

.upload-area {
  padding: 8px 4px;
}

.upload-btn {
  width: 100%;
  padding: 10px;
  background: #19c37d;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.2s;
}

.upload-btn:hover:not(:disabled) {
  background: #15a76b;
}

.upload-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.hint {
  color: #8e8ea0;
  font-size: 12px;
  margin-top: 8px;
  line-height: 1.5;
}

.doc-list {
  margin-top: 12px;
}

.doc-item {
  display: flex;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 8px;
  margin-bottom: 2px;
  transition: background 0.15s;
}

.doc-item:hover {
  background: #2b2c2f;
}

.doc-info {
  flex: 1;
  min-width: 0;
}

.doc-name {
  display: block;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta {
  display: block;
  color: #8e8ea0;
  font-size: 12px;
  margin-top: 4px;
}

.status {
  margin-left: 4px;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
}

.status.ready {
  background: rgba(25, 195, 125, 0.2);
  color: #19c37d;
}

.status.failed,
.status.processing {
  background: rgba(245, 158, 11, 0.2);
  color: #f59e0b;
}

.doc-error {
  display: block;
  color: #ef4444;
  font-size: 12px;
  margin-top: 4px;
}

.doc-delete {
  background: none;
  border: none;
  color: #8e8ea0;
  font-size: 18px;
  cursor: pointer;
  padding: 0 4px;
  opacity: 0;
  transition: opacity 0.15s, color 0.15s;
}

.doc-item:hover .doc-delete {
  opacity: 1;
}

.doc-delete:hover {
  color: #ef4444;
}

.empty-hint {
  text-align: center;
  color: #8e8ea0;
  font-size: 13px;
  margin-top: 24px;
}
</style>
