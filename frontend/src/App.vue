<template>
  <div class="app-container">
    <SessionList
      :sessions="sessions"
      :activeId="activeSessionId"
      :tab="sidebarTab"
      @select="selectSession"
      @create="createNewSession"
      @delete="deleteCurrentSession"
      @switch-tab="sidebarTab = $event"
    >
      <template #kb>
        <KnowledgeBasePanel
          :documents="documents"
          :uploading="uploading"
          @upload="handleUpload"
          @delete="handleDeleteDocument"
        />
      </template>
    </SessionList>
    <div class="main-area">
      <div v-if="loadError" class="load-error">
        ⚠️ {{ loadError }}
        <button class="retry-btn" @click="reloadAll">重试</button>
      </div>
      <ChatWindow
        :sessionId="activeSessionId"
        :messages="currentMessages"
        :loading="isLoading"
        @send="handleSend"
        ref="chatWindow"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import SessionList from './components/SessionList.vue'
import ChatWindow from './components/ChatWindow.vue'
import KnowledgeBasePanel from './components/KnowledgeBasePanel.vue'
import { fetchSessions, createSession, deleteSession, fetchMessages, sendMessage } from './api/chat.js'
import { uploadDocument, fetchDocuments, deleteDocument } from './api/documents.js'

const sessions = ref([])
const activeSessionId = ref(null)
const messagesMap = ref({})  // { sessionId: [messages] }
const isLoading = ref(false)
const chatWindow = ref(null)

const sidebarTab = ref('chat')
const documents = ref([])
const uploading = ref(false)
const loadError = ref('')

const currentMessages = computed(() => {
  return messagesMap.value[activeSessionId.value] || []
})

async function reloadAll() {
  loadError.value = ''
  const list = await fetchSessions()
  sessions.value = list
  if (list.length > 0) {
    await selectSession(list[0].id)
  }
  documents.value = await fetchDocuments()
}

onMounted(async () => {
  try {
    await reloadAll()
  } catch (err) {
    loadError.value = '无法连接后端服务，请确认后端已启动（http://localhost:8080）'
    console.error('Failed to load initial data:', err)
  }
})

async function createNewSession() {
  const session = await createSession()
  sessions.value.unshift(session)
  messagesMap.value[session.id] = []
  activeSessionId.value = session.id
}

async function selectSession(id) {
  activeSessionId.value = id
  if (!messagesMap.value[id]) {
    const msgs = await fetchMessages(id)
    messagesMap.value[id] = msgs.map(m => ({
      id: m.id,
      role: m.role,
      content: m.content,
      citations: m.citations || null,
    }))
  }
}

async function deleteCurrentSession(id) {
  await deleteSession(id)
  sessions.value = sessions.value.filter(s => s.id !== id)
  delete messagesMap.value[id]
  if (activeSessionId.value === id) {
    activeSessionId.value = sessions.value.length > 0 ? sessions.value[0].id : null
    if (activeSessionId.value) {
      await selectSession(activeSessionId.value)
    }
  }
}

function handleSend(message) {
  if (!activeSessionId.value) return

  // Add user message
  if (!messagesMap.value[activeSessionId.value]) {
    messagesMap.value[activeSessionId.value] = []
  }
  messagesMap.value[activeSessionId.value].push({ role: 'user', content: message })

  // Add placeholder for AI response.
  // Must be reactive() so per-token content updates re-render the DOM;
  // a plain object pushed into the array would bypass Vue's proxy.
  const aiMsg = reactive({ role: 'assistant', content: '', streaming: true, citations: [] })
  messagesMap.value[activeSessionId.value].push(aiMsg)

  isLoading.value = true

  // Stall watchdog: if no token arrives for 60s (LLM stream interrupted
  // without a done/error event), reset the UI instead of hanging forever.
  let stallTimer = null
  const armStallWatchdog = () => {
    clearTimeout(stallTimer)
    stallTimer = setTimeout(() => {
      if (aiMsg.streaming) {
        aiMsg.content = aiMsg.content || '响应超时，请重试'
        aiMsg.streaming = false
        isLoading.value = false
      }
    }, 60_000)
  }
  armStallWatchdog() // covers the first-token wait too

  sendMessage(activeSessionId.value, message, {
    onCitations(citations) {
      aiMsg.citations = citations
    },
    onToken(token) {
      aiMsg.content += token
      armStallWatchdog()
    },
    onDone() {
      clearTimeout(stallTimer)
      aiMsg.streaming = false
      isLoading.value = false
      // Refresh session list to update title/timestamp
      refreshSessions()
    },
    onError(err) {
      clearTimeout(stallTimer)
      aiMsg.content = '错误: ' + err.message
      aiMsg.streaming = false
      isLoading.value = false
    }
  })
}

async function handleUpload(file) {
  uploading.value = true
  try {
    await uploadDocument(file)
    documents.value = await fetchDocuments()
  } catch (err) {
    alert(err.message)
  } finally {
    uploading.value = false
  }
}

async function handleDeleteDocument(doc) {
  if (!confirm(`删除文档「${doc.fileName}」？其向量也会从知识库移除。`)) return
  try {
    await deleteDocument(doc.id)
    documents.value = await fetchDocuments()
  } catch (err) {
    alert(err.message)
  }
}

async function refreshSessions() {
  const list = await fetchSessions()
  sessions.value = list
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: #343541;
  color: #ececf1;
  overflow: hidden;
}

.app-container {
  display: flex;
  height: 100vh;
}

.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.load-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: rgba(239, 68, 68, 0.15);
  color: #fca5a5;
  font-size: 14px;
}

.retry-btn {
  padding: 4px 12px;
  background: transparent;
  color: #fca5a5;
  border: 1px solid #fca5a5;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.retry-btn:hover {
  background: rgba(239, 68, 68, 0.2);
}
</style>
