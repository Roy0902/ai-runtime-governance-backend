package local.governance.admin;

public class AdminException extends RuntimeException {

    private final int exitCode;
    private final String code;

    public AdminException(int exitCode, String code, String message) {
        super(message);
        this.exitCode = exitCode;
        this.code = code;
    }

    public int exitCode() {
        return exitCode;
    }

    public String code() {
        return code;
    }
}
