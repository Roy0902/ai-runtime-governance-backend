package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import local.governance.api.APIException;
import org.junit.jupiter.api.Test;

class GatewayRequestParserTest {

    private final GatewayRequestParser parser = new GatewayRequestParser(new ObjectMapper());

    @Test
    void findsTheNewHumanTurnWhenTheFinalMessageIsAUserMessage() {
        GatewayRequestParser.Parsed parsed = parser.parse(bytes("""
                {
                  "model": "gpt-4o",
                  "messages": [
                    {"role": "system", "content": "be brief"},
                    {"role": "user", "content": "hello"}
                  ]
                }
                """));

        assertEquals(GatewayRequestParser.Mode.NEW_TURN, parsed.mode());
        assertEquals(1, parsed.userMessageIndex());
        assertEquals("hello", parsed.userText());
        assertEquals("gpt-4o", parsed.model());
        assertTrue(!parsed.stream());
    }

    @Test
    void treatsAToolContinuationAsPartOfTheEarlierGuardedTurn() {
        GatewayRequestParser.Parsed parsed = parser.parse(bytes("""
                {
                  "model": "gpt-4o",
                  "messages": [
                    {"role": "user", "content": "check the weather"},
                    {"role": "assistant", "content": null,
                     "tool_calls": [{"id": "call-1", "type": "function",
                       "function": {"name": "weather", "arguments": "{}"}}]},
                    {"role": "tool", "tool_call_id": "call-1", "content": "rain"}
                  ]
                }
                """));

        assertEquals(GatewayRequestParser.Mode.CONTINUATION, parsed.mode());
        assertEquals(0, parsed.userMessageIndex());
        assertEquals("check the weather", parsed.userText());
    }

    @Test
    void canonicalPrefixIsStableAcrossFieldOrdering() {
        GatewayRequestParser.Parsed first = parser.parse(bytes("""
                {"model":"m","messages":[{"role":"user","content":"hi"}]}
                """));
        GatewayRequestParser.Parsed second = parser.parse(bytes("""
                {"model":"m","messages":[{"content":"hi","role":"user"}]}
                """));

        assertEquals(first.canonicalPrefix(), second.canonicalPrefix());
    }

    @Test
    void acceptsMultimodalContentAndRepresentsImagesWithAPlaceholder() {
        GatewayRequestParser.Parsed parsed = parser.parse(bytes("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"hello"},
                  {"type":"image_url","image_url":{"url":"https://example.test/x.png"}},
                  {"type":"text","text":"what is this?"}
                ]}]}
                """));

        assertEquals(GatewayRequestParser.Mode.NEW_TURN, parsed.mode());
        assertEquals("hello\n[image]\nwhat is this?", parsed.userText());
    }

    @Test
    void acceptsImageOnlyContent() {
        GatewayRequestParser.Parsed parsed = parser.parse(bytes("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"input_image","image_url":"data:image/png;base64,AAAA"}
                ]}]}
                """));

        assertEquals(GatewayRequestParser.IMAGE_PLACEHOLDER, parsed.userText());
    }

    @Test
    void discardsNonTextNonImageParts() {
        GatewayRequestParser.Parsed parsed = parser.parse(bytes("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"input_audio","input_audio":{"data":"AAAA","format":"wav"}},
                  {"type":"input_text","text":"describe"},
                  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}},
                  {"type":"file","file":{"file_id":"file-1"}}
                ]}]}
                """));

        assertEquals("describe\n[image]", parsed.userText());
    }

    @Test
    void rejectsContentWithoutTextOrImages() {
        APIException exception = assertThrows(APIException.class, () -> parser.parse(bytes("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"input_audio","input_audio":{"data":"AAAA","format":"wav"}}
                ]}]}
                """)));

        assertEquals("PROTOCOL_UNSUPPORTED", exception.code());
    }

    @Test
    void rejectsAConversationWithoutAUserTurn() {
        APIException exception = assertThrows(APIException.class, () -> parser.parse(bytes("""
                {"model":"m","messages":[{"role":"system","content":"hello"}]}
                """)));

        assertEquals("GUARD_CONTEXT_UNKNOWN", exception.code());
    }

    @Test
    void acceptsALargeSubmittedMessage() {
        String large = "a".repeat(200_000);
        GatewayRequestParser.Parsed parsed = parser.parse(bytes(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + large + "\"}]}"));

        assertEquals(GatewayRequestParser.Mode.NEW_TURN, parsed.mode());
        assertEquals(large, parsed.userText());
    }

    @Test
    void rejectsUnsupportedRolesBeforeForwarding() {
        APIException exception = assertThrows(APIException.class, () -> parser.parse(bytes("""
                {"model":"m","messages":[{"role":"robot","content":"hello"}]}
                """)));

        assertEquals("REQUEST_INVALID", exception.code());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
