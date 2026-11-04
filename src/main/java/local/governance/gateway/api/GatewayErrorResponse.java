package local.governance.gateway.api;

public record GatewayErrorResponse(Error error) {

    public record Error(String message, String type, String param, String code) {}
}
