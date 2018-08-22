package org.apache.cordova.plugin;

import android.annotation.TargetApi;
import android.os.Build;
import android.util.Log;

import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public class NanoHTTPDWebserver extends NanoHTTPD {

    Webserver webserver;

    public NanoHTTPDWebserver(int port, Webserver webserver) {
        super(port);
        this.webserver = webserver;
    }

    private String getBodyText(IHTTPSession session) {
        Map<String, String> files = new HashMap<String, String>();
        Method method = session.getMethod();
        if (Method.PUT.equals(method) || Method.POST.equals(method)) {
            try {
                session.parseBody(files);
            } catch (IOException ioe) {
                return "{}";
            } catch (ResponseException re) {
                return "{}";
            }
        } else if (Method.PATCH.equals(method)) {
            try {
                this.parsePatchBody(session, files);
            } catch (IOException ioe) {
                return "{}";
            } catch (ResponseException re) {
                return "{}";
            }
        }
        // get the POST body
        return files.get("postData");
    }

    private long getBodySize(IHTTPSession session) {
        if (session.getHeaders().containsKey("content-length")) {
            return Long.parseLong(session.getHeaders().get("content-length"));
        }
        return 0;
    }

    private void parsePatchBody(IHTTPSession session, Map<String, String> files) throws IOException, ResponseException {
        try {
            long size = this.getBodySize(session);
            ByteArrayOutputStream baos = null;
            DataOutput requestDataOutput = null;

            // Store the request in memory or a file, depending on size
            if (size < 2048) {
                baos = new ByteArrayOutputStream();
                requestDataOutput = new DataOutputStream(baos);
            } else {
                throw new ResponseException(Response.Status.INTERNAL_ERROR,
                        "INTERNAL ERROR: Patch body size exceed 2048 bytes");
            }

            // Read all the body and write it to request_data_output
            byte[] buf = new byte[512];
            int rlen = 0;
            while (rlen >= 0 && size > 0) {
                rlen = session.getInputStream().read(buf, 0, (int) Math.min(size, 512));
                size -= rlen;
                if (rlen > 0) {
                    requestDataOutput.write(buf, 0, rlen);
                }
            }

            ByteBuffer fbuf = null;
            if (baos != null) {
                fbuf = ByteBuffer.wrap(baos.toByteArray(), 0, baos.size());
            }

            ContentType contentType = new ContentType(session.getHeaders().get("content-type"));
            byte[] postBytes = new byte[fbuf.remaining()];
            fbuf.get(postBytes);
            String postLine = new String(postBytes, contentType.getEncoding()).trim();

            if (postLine.length() != 0) {
                files.put("postData", postLine);
            }

        } finally {
        }
    }

    /**
     * Create a request object
     * <p>
     * [ "requestId": requestUUID, " body": request.jsonObject ?? "", " headers":
     * request.headers, " method": request.method, " path": request.url.path, "
     * query": request.url.query ?? "" ]
     *
     * @param session
     * @return
     */
    private JSONObject createJSONRequest(String requestId, IHTTPSession session) throws JSONException {
        JSONObject jsonRequest = new JSONObject();
        jsonRequest.put("requestId", requestId);
        jsonRequest.put("body", this.getBodyText(session));
        jsonRequest.put("headers", session.getHeaders());
        jsonRequest.put("method", session.getMethod());
        jsonRequest.put("path", session.getUri());
        jsonRequest.put("query", session.getQueryParameterString());
        return jsonRequest;
    }

    private String getContentType(JSONObject responseObject) throws JSONException {
        if (responseObject.has("headers") && responseObject.getJSONObject("headers").has("Content-Type")) {
            return responseObject.getJSONObject("headers").getString("Content-Type");
        } else {
            return "text/plain";
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        Log.d(this.getClass().getName(), "New request is incoming!");

        String requestUUID = UUID.randomUUID().toString();

        PluginResult pluginResult = null;
        try {
            pluginResult = new PluginResult(PluginResult.Status.OK, this.createJSONRequest(requestUUID, session));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pluginResult.setKeepCallback(true);
        this.webserver.onRequestCallbackContext.sendPluginResult(pluginResult);

        while (!this.webserver.responses.containsKey(requestUUID)) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        JSONObject responseObject = (JSONObject) this.webserver.responses.get(requestUUID);
        Log.d(this.getClass().getName(), "responseObject: " + responseObject.toString());
        Response response = null;

        try {
            response = newFixedLengthResponse(Response.Status.lookup(responseObject.getInt("status")),
                    getContentType(responseObject), responseObject.getString("body"));

            Iterator<?> keys = responseObject.getJSONObject("headers").keys();
            while (keys.hasNext()) {
                String key = (String) keys.next();
                response.addHeader(key, responseObject.getJSONObject("headers").getString(key));
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
        return response;
    }
}
