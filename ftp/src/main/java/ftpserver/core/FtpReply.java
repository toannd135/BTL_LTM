package ftpserver.core;

/** Hang so ma dap ung FTP theo RFC 959 muc 4.2.2. */
public final class FtpReply {
    private FtpReply() {}

    public static final int FILE_STATUS_OK_OPENING   = 150;
    public static final int SYSTEM_STATUS              = 211;
    public static final int FILE_STATUS                 = 213;
    public static final int AUTH_OK                     = 234;
    public static final int PENDING_FURTHER_INFO       = 350;
    public static final int PROT_DENIED_POLICY         = 534;
    public static final int COMMAND_OK                = 200;
    public static final int SYSTEM_TYPE                = 215;
    public static final int SERVICE_READY              = 220;
    public static final int SERVICE_CLOSING            = 221;
    public static final int FILE_ACTION_OK             = 250;
    public static final int DIR_ACTION_OK               = 257;
    public static final int USERNAME_OK_NEED_PASSWORD  = 331;
    public static final int LOGGED_IN                   = 230;
    public static final int CLOSING_DATA_CONN          = 226;
    public static final int ENTERING_PASSIVE           = 227;
    public static final int SERVICE_NOT_AVAILABLE      = 421;
    public static final int CANT_OPEN_DATA_CONN        = 425;
    public static final int CONN_CLOSED_ABORTED        = 426;
    public static final int FILE_ACTION_NOT_TAKEN      = 450;
    public static final int LOCAL_ERROR                 = 451;
    public static final int INSUFFICIENT_STORAGE       = 452;
    public static final int NOT_LOGGED_IN                = 530;
    public static final int FILE_UNAVAILABLE            = 550;
    public static final int FILE_NAME_NOT_ALLOWED      = 553;
    public static final int SYNTAX_ERROR_COMMAND       = 500;
    public static final int SYNTAX_ERROR_PARAMS        = 501;
    public static final int COMMAND_NOT_IMPLEMENTED    = 502;
    public static final int BAD_SEQUENCE                 = 503;
    public static final int PARAM_NOT_IMPLEMENTED      = 504;
}
