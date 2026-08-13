export function onEnterSubmit(e, callback) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        callback()
    }
}
