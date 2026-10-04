/** The server's rule for handles: 3 to 32 characters, a letter first, no dash last. */
export const HANDLE_PATTERN = /^[a-z][a-z0-9-]{1,30}[a-z0-9]$/

const MAX_LENGTH = 32

const MIN_LENGTH = 3

/**
 * A handle to offer someone choosing one, made from their sign-in nickname. Reserved names are left
 * to the server, which says so at the field.
 */
export function suggestHandle(nickname: string): string {
    let handle = nickname
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "")
    if (!/^[a-z]/.test(handle)) handle = `u-${handle}`
    handle = handle.slice(0, MAX_LENGTH).replace(/-+$/, "")
    while (handle.length < MIN_LENGTH) handle = `${handle}0`
    return handle
}
