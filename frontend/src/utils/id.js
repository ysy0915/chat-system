export function generateId() {
    return Date.now().toString(36) + '-' + Math.random().toString(36).substring(2, 10)
}

export function getOrCreateUserId() {
    let id = localStorage.getItem('chat_user_id')
    if (!id) {
        id = generateId()
        localStorage.setItem('chat_user_id', id)
    }
    return id
}
