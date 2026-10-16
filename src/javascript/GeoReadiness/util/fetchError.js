// The exception classes a server-side fetch records (SiteFilesChecker), each with a hint in plain words.
// Any other class falls back to a generic sentence that still names it.
const KNOWN = new Set(['ConnectException', 'SocketTimeoutException', 'SSLHandshakeException', 'UnknownHostException']);

/** Why a fetch failed, for a reader, or '' when nothing was recorded. */
export const fetchErrorHint = (t, error) => {
    if (!error) {
        return '';
    }

    return t(`fetchError.${KNOWN.has(error) ? error : 'other'}`, {error});
};
