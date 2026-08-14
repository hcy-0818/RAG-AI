<template>
  <aside class="sidebar">
    <div class="tabs">
      <button
        :class="['tab', { active: tab === 'chat' }]"
        @click="$emit('switch-tab', 'chat')"
      >💬 对话</button>
      <button
        :class="['tab', { active: tab === 'kb' }]"
        @click="$emit('switch-tab', 'kb')"
      >📚 知识库</button>
    </div>

    <template v-if="tab === 'chat'">
      <button class="new-chat-btn" @click="$emit('create')">
        + 新建对话
      </button>

      <div class="session-list">
        <div
          v-for="session in sessions"
          :key="session.id"
          :class="['session-item', { active: session.id === activeId }]"
          @click="$emit('select', session.id)"
        >
          <span class="session-title">{{ session.title }}</span>
          <button
            class="delete-btn"
            @click.stop="$emit('delete', session.id)"
            title="删除会话"
          >×</button>
        </div>

        <div v-if="sessions.length === 0" class="empty-hint">
          暂无对话，点击上方按钮创建
        </div>
      </div>
    </template>

    <slot v-else name="kb" />
  </aside>
</template>

<script setup>
defineProps({
  sessions: { type: Array, default: () => [] },
  activeId: { type: String, default: null },
  tab: { type: String, default: 'chat' },
})

defineEmits(['select', 'create', 'delete', 'switch-tab'])
</script>

<style scoped>
.sidebar {
  width: 260px;
  min-width: 260px;
  background: #202123;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #4d4d4f;
}

.tabs {
  display: flex;
  padding: 12px 12px 0;
  gap: 8px;
}

.tab {
  flex: 1;
  padding: 8px 0;
  background: transparent;
  color: #8e8ea0;
  border: 1px solid #565869;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.2s, color 0.2s;
}

.tab:hover {
  background: #2b2c2f;
}

.tab.active {
  background: #343541;
  color: #ececf1;
}

.new-chat-btn {
  margin: 12px;
  padding: 12px;
  background: transparent;
  color: #ececf1;
  border: 1px solid #565869;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.2s;
}

.new-chat-btn:hover {
  background: #2b2c2f;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
}

.session-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 2px;
  transition: background 0.15s;
}

.session-item:hover {
  background: #2b2c2f;
}

.session-item.active {
  background: #343541;
}

.session-title {
  flex: 1;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.delete-btn {
  background: none;
  border: none;
  color: #8e8ea0;
  font-size: 18px;
  cursor: pointer;
  padding: 0 4px;
  opacity: 0;
  transition: opacity 0.15s, color 0.15s;
}

.session-item:hover .delete-btn {
  opacity: 1;
}

.delete-btn:hover {
  color: #ef4444;
}

.empty-hint {
  text-align: center;
  color: #8e8ea0;
  font-size: 13px;
  margin-top: 24px;
}
</style>
