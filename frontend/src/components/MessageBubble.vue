<template>
  <div :class="['message-row', role]">
    <div class="avatar">
      {{ role === 'user' ? '👤' : '🤖' }}
    </div>
    <div class="bubble">
      <!-- assistant: plain text while streaming (avoids half-open ** artifacts),
           rendered Markdown (sanitized) once complete; user: plain text -->
      <div
        v-if="role === 'assistant' && !streaming"
        class="content md-content"
        v-html="renderedContent"
      ></div>
      <div v-else class="content" v-text="content"></div>
      <span v-if="streaming" class="cursor">▍</span>

      <div v-if="role === 'assistant' && citations && citations.length" class="citations">
        <div class="cite-title">📎 引用来源</div>
        <div v-for="(c, i) in citations" :key="i" class="cite-item">
          [{{ i + 1 }}] {{ c.docName }}（第{{ c.chunkIndex + 1 }}段）
          <div class="cite-snippet">{{ c.snippet }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  role: { type: String, required: true },  // 'user' | 'assistant'
  content: { type: String, default: '' },
  streaming: { type: Boolean, default: false },
  citations: { type: Array, default: null },
})

/**
 * LLMs often emit malformed lists like "。-项目A。-项目B" (dash glued to
 * text, no newline). Normalize each such dash into a proper list line so
 * marked renders a real <ul> instead of raw "-" characters.
 */
function normalizeMalformedListDashes(text) {
  return text.replace(/([。；])\s*-+(?=\S)/g, '$1\n\n- ')
}

const renderedContent = computed(() => {
  const html = marked.parse(normalizeMalformedListDashes(props.content || ''), {
    async: false,
    breaks: true, // keep single newlines as <br> (chat-style line breaks)
  })
  return DOMPurify.sanitize(html)
})
</script>

<style scoped>
.message-row {
  display: flex;
  padding: 20px 16%;
  gap: 16px;
}

.message-row.user {
  background: #343541;
}

.message-row.assistant {
  background: #444654;
}

.avatar {
  font-size: 28px;
  flex-shrink: 0;
  width: 36px;
  text-align: center;
}

.bubble {
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.cursor {
  display: inline;
  animation: blink 1s step-end infinite;
  color: #ececf1;
}

/* Rendered markdown typography (v-html content, so :deep) */
.md-content :deep(p) {
  margin: 6px 0;
}

.md-content :deep(ul),
.md-content :deep(ol) {
  margin: 6px 0;
  padding-left: 22px;
}

.md-content :deep(li) {
  margin: 3px 0;
}

.md-content :deep(strong) {
  font-weight: 600;
}

.md-content :deep(h1),
.md-content :deep(h2),
.md-content :deep(h3) {
  margin: 10px 0 6px;
  font-size: 1.1em;
}

.md-content :deep(code) {
  background: rgba(0, 0, 0, 0.3);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.9em;
}

.md-content :deep(blockquote) {
  border-left: 3px solid #565869;
  padding-left: 10px;
  margin: 6px 0;
  color: #c5c5d2;
}

.citations {
  margin-top: 12px;
  padding: 10px 12px;
  border-top: 1px solid #565869;
  font-size: 12px;
}

.cite-title {
  color: #8e8ea0;
  margin-bottom: 6px;
}

.cite-item {
  color: #c5c5d2;
  margin-bottom: 6px;
}

.cite-snippet {
  margin-top: 2px;
  padding-left: 12px;
  border-left: 2px solid #565869;
  color: #8e8ea0;
  line-height: 1.5;
}

@keyframes blink {
  50% { opacity: 0; }
}
</style>
