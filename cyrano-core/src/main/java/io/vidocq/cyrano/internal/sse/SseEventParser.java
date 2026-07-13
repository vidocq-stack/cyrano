/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.cyrano.internal.sse;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental, pure (no I/O) parser of the {@code text/event-stream} protocol
 * — WHATWG HTML "Server-sent events", §9.2.6 "Interpreting an event stream".
 * Used by the MP Rest Client 4.0 SSE support (spec §10, reactive Publisher
 * return types).
 *
 * <p>Feed it decoded character chunks (any granularity — chunk boundaries may
 * fall anywhere, including inside a line); it returns the events completed by
 * that chunk. An event is dispatched on an empty line only; a pending event
 * left incomplete at end of stream is never dispatched (per WHATWG).</p>
 *
 * <p>Field semantics implemented: {@code data:} (multiple lines joined with
 * {@code \n}), {@code event:}, {@code id:} (persists across events, ignored
 * when it contains NUL), {@code retry:} (digits only), and comment lines
 * starting with {@code :} (dispatched even without data — MP Rest Client TCK
 * relies on comment-only events). A single leading space after the colon is
 * stripped; a line without a colon is a field name with an empty value;
 * unknown field names are ignored. Line terminators: CRLF, LF, or CR.</p>
 *
 * <p>Not thread-safe by design: one parser per connection, fed from the
 * single-threaded response-body subscriber.</p>
 */
public final class SseEventParser {

    /**
     * One parsed SSE event, immutable.
     *
     * @param name        value of the {@code event:} field, or {@code null}
     * @param id          last seen {@code id:} value (persistent), or {@code null}
     * @param comment     comment lines joined with {@code \n}, or {@code null}
     * @param data        data lines joined with {@code \n}, or {@code null} if
     *                    no {@code data:} field appeared in the event
     * @param retryMillis value of the {@code retry:} field, or {@code null}
     */
    public record SseEventData(String name, String id, String comment, String data, Long retryMillis) {
    }

    private final StringBuilder lineBuffer = new StringBuilder();
    private final List<String> dataLines = new ArrayList<>();
    private final List<String> commentLines = new ArrayList<>();
    private String eventName;
    private String lastEventId;
    private Long retryMillis;
    private boolean sawField;
    private boolean sawCarriageReturn;
    private boolean atStreamStart = true;

    /**
     * Feeds a chunk of decoded characters and returns the events completed by it.
     */
    public List<SseEventData> feed(CharSequence chunk) {
        List<SseEventData> out = new ArrayList<>();
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (atStreamStart) {
                atStreamStart = false;
                if (c == 0xFEFF) { //U+FEFF byte-order mark
                    continue; //WHATWG: one leading BOM is skipped
                }
            }
            if (sawCarriageReturn) {
                sawCarriageReturn = false;
                if (c == '\n') {
                    continue; //CRLF: the line was already processed on CR
                }
            }
            switch (c) {
                case '\r' -> {
                    processLine(out);
                    sawCarriageReturn = true;
                }
                case '\n' -> processLine(out);
                default -> lineBuffer.append(c);
            }
        }
        return out;
    }

    private void processLine(List<SseEventData> out) {
        String line = lineBuffer.toString();
        lineBuffer.setLength(0);

        if (line.isEmpty()) {
            //Blank line: dispatch if anything was accumulated for this event.
            if (sawField) {
                out.add(buildEvent());
                resetEvent();
            }
            return;
        }
        if (line.charAt(0) == ':') {
            commentLines.add(stripLeadingSpace(line.substring(1)));
            sawField = true;
            return;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : stripLeadingSpace(line.substring(colon + 1));
        switch (field) {
            case "data" -> {
                dataLines.add(value);
                sawField = true;
            }
            case "event" -> {
                eventName = value;
                sawField = true;
            }
            case "id" -> {
                //WHATWG: an id containing NUL is ignored.
                if (value.indexOf('\0') < 0) {
                    lastEventId = value;
                    sawField = true;
                }
            }
            case "retry" -> {
                //WHATWG: value must consist of ASCII digits only, otherwise ignored.
                if (!value.isEmpty() && value.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                    retryMillis = Long.parseLong(value);
                    sawField = true;
                }
            }
            default -> {
                //Unknown field: ignored entirely (does not trigger dispatch).
            }
        }
    }

    private SseEventData buildEvent() {
        return new SseEventData(
                eventName,
                lastEventId,
                commentLines.isEmpty() ? null : String.join("\n", commentLines),
                dataLines.isEmpty() ? null : String.join("\n", dataLines),
                retryMillis);
    }

    private void resetEvent() {
        dataLines.clear();
        commentLines.clear();
        eventName = null;
        retryMillis = null;
        sawField = false;
        //lastEventId deliberately persists (WHATWG "last event ID" buffer).
    }

    private static String stripLeadingSpace(String s) {
        return !s.isEmpty() && s.charAt(0) == ' ' ? s.substring(1) : s;
    }
}
