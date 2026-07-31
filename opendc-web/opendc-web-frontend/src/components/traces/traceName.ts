/**
 * The rule the server holds a trace name to, checked here so a bad character shows under the field
 * as it is typed rather than coming back as a notification once the upload has been attempted.
 *
 * The server trims and lower-cases before it checks, and this does the same: "MyTrace" is accepted
 * and stored as "mytrace", while "My Trace" is refused for the space rather than the capitals.
 */
const USABLE_NAME = /^[a-z0-9][a-z0-9._-]{0,99}$/

export const TRACE_NAME_HELP =
    "Lower-case letters, digits, dots, dashes and underscores, starting with a letter or a digit. Your handle stays in front, so only this part is yours to choose."

/** What is wrong with [name], or nothing when the server will take it. */
export function traceNameProblem(name: string): string | undefined {
    const cleaned = name.trim().toLowerCase()
    if (cleaned === "") return "A name is needed."
    if (cleaned.length > 100) return "That is too long."
    if (!USABLE_NAME.test(cleaned)) {
        return "Use lower-case letters, digits, dots, dashes and underscores, starting with a letter or a digit."
    }
    return undefined
}
