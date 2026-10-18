package br.local.mdnsfinder;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.List;

/** One screen: type a ".local" name, get its IP address, open it in the browser. */
public class MainActivity extends Activity {

    private static final String PREFS = "prefs";
    private static final String KEY_NAME = "name";

    private EditText nameInput;
    private Button findButton;
    private Button openButton;
    private TextView status;
    private TextView result;

    private String url;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText(Msg.NAME_LABEL.text());
        root.addView(label, matchWrap());

        nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint(Msg.NAME_HINT.text());
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        nameInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, ""));
        nameInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                find();
                return true;
            }
        });
        root.addView(nameInput, matchWrap());

        findButton = new Button(this);
        findButton.setText(Msg.FIND.text());
        findButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                find();
            }
        });
        root.addView(findButton, matchWrap());

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, dp(4));
        root.addView(status, matchWrap());

        result = new TextView(this);
        result.setTextSize(26);
        result.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        result.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 11) {
            result.setTextIsSelectable(true); // long-press to copy
        }
        root.addView(result, matchWrap());

        openButton = new Button(this);
        openButton.setText(Msg.OPEN.text());
        openButton.setVisibility(View.GONE);
        openButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openBrowser();
            }
        });
        root.addView(openButton, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void find() {
        if (busy) {
            return;
        }
        String raw = nameInput.getText().toString();
        final String host = Mdns.normalizeHost(raw);
        final int port = Mdns.parsePort(raw);
        if (host.length() == 0) {
            status.setText(Msg.EMPTY_NAME.text());
            return;
        }
        try {
            Mdns.encodeName(host); // reject names that cannot be sent
        } catch (IllegalArgumentException e) {
            status.setText(Msg.BAD_NAME.text());
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_NAME, raw.trim()).commit();

        busy = true;
        findButton.setEnabled(false);
        result.setVisibility(View.GONE);
        openButton.setVisibility(View.GONE);
        status.setText(Msg.SEARCHING.text());

        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                List<InetAddress> found = null;
                boolean networkError = false;
                WifiManager.MulticastLock lock = null;
                try {
                    WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        lock = wm.createMulticastLock("mdnsfinder");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    }
                    found = Mdns.resolve(host);
                } catch (java.io.IOException e) {
                    networkError = true;
                } catch (RuntimeException e) {
                    networkError = true;
                } finally {
                    if (lock != null && lock.isHeld()) {
                        lock.release();
                    }
                }
                final List<InetAddress> addresses = found;
                final boolean failed = networkError;
                runOnUiThread(new Runnable() {
                    public void run() {
                        show(host, port, addresses, failed);
                    }
                });
            }
        }).start();
    }

    private void show(String host, int port, List<InetAddress> addresses, boolean networkError) {
        busy = false;
        findButton.setEnabled(true);
        if (networkError) {
            status.setText(Msg.NO_NETWORK.text());
            return;
        }
        if (addresses == null || addresses.isEmpty()) {
            status.setText(Msg.NOT_FOUND.text());
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (InetAddress a : addresses) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String s = a.getHostAddress();
            int scope = s.indexOf('%');
            sb.append(scope >= 0 ? s.substring(0, scope) : s);
        }
        result.setText(sb.toString());
        result.setVisibility(View.VISIBLE);

        InetAddress best = Mdns.bestForBrowser(addresses);
        if (best == null) {
            status.setText(Msg.ONLY_LINK_LOCAL.text());
            return;
        }
        status.setText(Msg.FOUND.text() + ": " + host);
        url = Mdns.browserUrl(best, port);
        openButton.setVisibility(View.VISIBLE);
    }

    private void openBrowser() {
        if (url == null) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, Msg.NO_BROWSER.text(), Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
