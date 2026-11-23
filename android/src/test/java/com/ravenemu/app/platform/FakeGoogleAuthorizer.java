package com.ravenemu.app.platform;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;

/** Google Play services' answers, as a test sets them: no Google screen opens. */
public final class FakeGoogleAuthorizer implements GoogleDriveAuth.Authorizer {
    /** What authorize answers: the token, Google's consent screen, or a failure. */
    public enum Mode { GRANT, CONSENT, FAIL }

    public Mode mode = Mode.GRANT;
    public String failure = "Les services Google Play sont absents";
    public final List<String> calls = new ArrayList<>();

    @Override public void authorize(Activity activity, GoogleDriveAuth.Answer answer) {
        calls.add("authorize");
        if (mode == Mode.GRANT) answer.granted("token");
        else if (mode == Mode.FAIL) answer.failed(failure);
        else answer.consent(PendingIntent.getActivity(activity, 0, new Intent("test.google.consent"),
                PendingIntent.FLAG_IMMUTABLE).getIntentSender());
    }

    @Override public void result(Activity activity, Intent data, GoogleDriveAuth.Answer answer) {
        calls.add("result");
        answer.granted("token");
    }

    @Override public GoogleDriveHttp.Tokens tokens(Context context, String email) {
        calls.add("tokens " + email);
        return new GoogleDriveHttp.Tokens() {
            @Override public String token() { return "token"; }
            @Override public void refused(String token) {}
        };
    }
}
