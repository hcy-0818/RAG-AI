const BASE = '/api';

/**
 * Fetch all sessions.
 */
export async function fetchSessions() {
  const res = await fetch(`${BASE}/sessions`);
  return res.json();
}

/**
 * Create a new session.
 */
export async function createSession() {
  const res = await fetch(`${BASE}/sessions`, { method: 'POST' });
  return res.json();
}

/**
 * Delete a session.
 */
export async function deleteSession(id) {
  await fetch(`${BASE}/sessions/${id}`, { method: 'DELETE' });
}

/**
 * Fetch message history for a session.
 */
export async function fetchMessages(sessionId) {
  const res = await fetch(`${BASE}/sessions/${sessionId}/messages`);
  return res.json();
}

/**
 * Send a message and receive streaming response via SSE.
 *
 * SSE event format:
 *   event: citations   (sent first when RAG retrieved content)
 *   data: <JSON array of CitationVO>
 *
 *   event: token
 *   data: <partial text>
 *
 *   event: done
 *   data: [DONE]
 *
 *   event: error
 *   data: <error message>
 */
export function sendMessage(sessionId, message, callbacks) {
  const controller = new AbortController();

  fetch(`${BASE}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, message }),
    signal: controller.signal,
  }).then(async (response) => {
    if (!response.ok) {
      callbacks.onError(new Error(`HTTP ${response.status}`));
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let currentEvent = '';
    let dataLines = [];

    const dispatch = (event, data) => {
      switch (event) {
        case 'citations':
          try {
            callbacks.onCitations?.(JSON.parse(data));
          } catch (e) {
            console.warn('Failed to parse citations:', e);
          }
          break;
        case 'token':
          callbacks.onToken(data);
          break;
        case 'done':
          callbacks.onDone();
          break;
        case 'error':
          callbacks.onError(new Error(data));
          break;
      }
    };

    const processLines = (lines) => {
      for (const line of lines) {
        // Empty line = end of one SSE event: dispatch the accumulated data.
        // Per the SSE spec, multiple data: lines of one event are joined
        // with '\n' — essential when a token contains newlines (Spring
        // splits them into consecutive data: lines).
        if (line.trim() === '') {
          dispatch(currentEvent, dataLines.join('\n'));
          currentEvent = '';
          dataLines = [];
          continue;
        }
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          let data = line.slice(5);
          if (data.startsWith(' ')) data = data.slice(1); // optional single space after 'data:'
          dataLines.push(data);
        }
      }
    };

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      processLines(lines);
    }

    // The stream ended: flush whatever remains in the buffer. Without this,
    // a final event split across TCP segments (e.g. the trailing "done"
    // event) would be silently dropped and the UI would hang forever.
    if (buffer.trim() !== '') {
      processLines(buffer.split('\n'));
      dispatch(currentEvent, dataLines.join('\n'));
    }
  }).catch(err => {
    if (err.name !== 'AbortError') {
      callbacks.onError(err);
    }
  });

  return controller;
}
