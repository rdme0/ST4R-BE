package star.member.exception;

import star.common.exception.server.InternalServerException;

public class MemberNotFoundException extends InternalServerException {
    public MemberNotFoundException(String message) {
        super(message);
    }
}
