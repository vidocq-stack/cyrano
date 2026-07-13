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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SseEventParser} — incremental parser of the
 * {@code text/event-stream} protocol (WHATWG HTML "Server-sent events",
 * §9.2.6 "Interpreting an event stream"), used by the MP Rest Client 4.0
 * SSE support (spec §10, Publisher return types).
 */
class SseEventParserTest {

    @Test
    void dataOnlyEvent_is_dispatched_on_blank_line() {
        var parser = new SseEventParser();
        List<SseEventParser.SseEventData> events = parser.feed("data: hello\n\n");
        assertEquals(1, events.size());
        var ev = events.get(0);
        assertEquals("hello", ev.data());
        assertNull(ev.name());
        assertNull(ev.comment());
        assertNull(ev.id());
        assertNull(ev.retryMillis());
    }

    @Test
    void multiLineData_is_joined_with_newline() {
        var parser = new SseEventParser();
        var events = parser.feed("data: line1\ndata: line2\n\n");
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void namedEvent_captures_event_field() {
        var parser = new SseEventParser();
        var events = parser.feed("event: weather\ndata: sunny\n\n");
        assertEquals(1, events.size());
        assertEquals("weather", events.get(0).name());
        assertEquals("sunny", events.get(0).data());
    }

    @Test
    void idField_is_captured_and_persists_to_following_events() {
        var parser = new SseEventParser();
        var events = parser.feed("id: 42\ndata: a\n\ndata: b\n\n");
        assertEquals(2, events.size());
        assertEquals("42", events.get(0).id());
        //WHATWG: the "last event ID" persists across events until overwritten.
        assertEquals("42", events.get(1).id());
    }

    @Test
    void retryField_with_digits_is_parsed_and_nonNumeric_is_ignored() {
        var parser = new SseEventParser();
        var events = parser.feed("retry: 5000\ndata: x\n\nretry: abc\ndata: y\n\n");
        assertEquals(2, events.size());
        assertEquals(5000L, events.get(0).retryMillis());
        assertNull(events.get(1).retryMillis());
    }

    @Test
    void commentOnlyEvent_is_dispatched_with_comment_and_no_data() {
        var parser = new SseEventParser();
        var events = parser.feed(": heartbeat\n\n");
        assertEquals(1, events.size());
        assertEquals("heartbeat", events.get(0).comment());
        assertNull(events.get(0).data());
    }

    @Test
    void multipleCommentLines_are_joined_with_newline() {
        var parser = new SseEventParser();
        var events = parser.feed(": one\n: two\n\n");
        assertEquals(1, events.size());
        assertEquals("one\ntwo", events.get(0).comment());
    }

    @Test
    void fieldWithoutColon_is_treated_as_field_name_with_empty_value() {
        var parser = new SseEventParser();
        //WHATWG: a line with no colon is processed with the whole line as the
        //field name and the empty string as the value.
        var events = parser.feed("data\n\n");
        assertEquals(1, events.size());
        assertEquals("", events.get(0).data());
    }

    @Test
    void onlyOneLeadingSpace_after_colon_is_stripped() {
        var parser = new SseEventParser();
        var events = parser.feed("data:  spaced\n\n");
        assertEquals(1, events.size());
        assertEquals(" spaced", events.get(0).data());
    }

    @Test
    void noSpaceAfterColon_keeps_value_intact() {
        var parser = new SseEventParser();
        var events = parser.feed("data:tight\n\n");
        assertEquals(1, events.size());
        assertEquals("tight", events.get(0).data());
    }

    @Test
    void emptyStream_produces_no_event() {
        var parser = new SseEventParser();
        assertTrue(parser.feed("").isEmpty());
    }

    @Test
    void event_without_trailing_blank_line_at_eof_is_not_dispatched() {
        var parser = new SseEventParser();
        //WHATWG: "Once the end of file is reached, any pending data must be
        //discarded. (If the file ends in the middle of an event, before the
        //final empty line, the incomplete event is not dispatched.)"
        var events = parser.feed("data: incomplete\n");
        assertTrue(events.isEmpty());
    }

    @Test
    void crlf_cr_and_lf_line_endings_are_all_accepted() {
        var parser = new SseEventParser();
        var events = parser.feed("data: a\r\n\r\ndata: b\r\rdata: c\n\n");
        assertEquals(3, events.size());
        assertEquals("a", events.get(0).data());
        assertEquals("b", events.get(1).data());
        assertEquals("c", events.get(2).data());
    }

    @Test
    void events_split_across_chunk_boundaries_are_reassembled() {
        var parser = new SseEventParser();
        assertTrue(parser.feed("da").isEmpty());
        assertTrue(parser.feed("ta: he").isEmpty());
        assertTrue(parser.feed("llo\n").isEmpty());
        var events = parser.feed("\n");
        assertEquals(1, events.size());
        assertEquals("hello", events.get(0).data());
    }

    @Test
    void unknownField_is_ignored_and_does_not_trigger_dispatch() {
        var parser = new SseEventParser();
        //Unknown field alone: nothing accumulated, blank line dispatches nothing.
        assertTrue(parser.feed("foo: bar\n\n").isEmpty());
        //Unknown field mixed with data: only the data survives.
        var events = parser.feed("foo: bar\ndata: x\n\n");
        assertEquals(1, events.size());
        assertEquals("x", events.get(0).data());
    }

    @Test
    void leading_utf8_bom_is_skipped() {
        var parser = new SseEventParser();
        var events = parser.feed("﻿" + "data: x\n\n"); // first char is a literal U+FEFF BOM
        assertEquals(1, events.size());
        assertEquals("x", events.get(0).data());
    }

    @Test
    void empty_data_lines_accumulate_as_newlines() {
        var parser = new SseEventParser();
        var events = parser.feed("data:\ndata:\n\n");
        assertEquals(1, events.size());
        //Two empty data lines joined with \n.
        assertEquals("\n", events.get(0).data());
    }

    @Test
    void multiple_consecutive_blank_lines_do_not_dispatch_empty_events() {
        var parser = new SseEventParser();
        var events = parser.feed("data: x\n\n\n\n");
        assertEquals(1, events.size());
    }
}
