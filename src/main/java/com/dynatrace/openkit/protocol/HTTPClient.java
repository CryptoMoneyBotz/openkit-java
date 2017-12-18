/***************************************************
 * (c) 2016-2017 Dynatrace LLC
 *
 * @author: Christian Schwarzbauer
 */

package com.dynatrace.openkit.protocol;

import com.dynatrace.openkit.api.SSLTrustManager;
import com.dynatrace.openkit.core.configuration.HTTPClientConfiguration;
import com.dynatrace.openkit.protocol.ssl.SSLStrictTrustManager;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP client helper which abstracts the 3 basic request types:
 * - status check
 * - beacon send
 * - time sync
 */
public class HTTPClient {

	public enum RequestType {

		STATUS("Status"),				// status check
		BEACON("Beacon"),				// beacon send
		TIMESYNC("TimeSync");			// time sync

		private String requestName;

		RequestType(String requestName) {
			this.requestName = requestName;
		}

		public String getRequestName() {
			return requestName;
		}

	}

	// request type constants
	private static final String REQUEST_TYPE_MOBILE = "type=m";
	private static final String REQUEST_TYPE_TIMESYNC = "type=mts";

	// query parameter constants
	private static final String QUERY_KEY_SERVER_ID = "srvid";
	private static final String QUERY_KEY_APPLICATION = "app";
	private static final String QUERY_KEY_VERSION = "va";
	private static final String QUERY_KEY_PLATFORM_TYPE = "pt";

	// constant query parameter values
	private static final String PLATFORM_TYPE_OPENKIT = "1";

	// connection constants
	private static final int MAX_SEND_RETRIES = 3;
	private static final int RETRY_SLEEP_TIME = 200;		// retry sleep time in ms
	private static final int CONNECT_TIMEOUT = 5000;
	private static final int READ_TIMEOUT = 30000;

    // URLs for requests
	private final String monitorURL;
    private final String timeSyncURL;

    private final int serverID;
    private final boolean verbose;

    private final SSLTrustManager sslTrustManager;

	// *** constructors ***

    public HTTPClient(HTTPClientConfiguration configuration) {
    	serverID = configuration.getServerID();
    	verbose = configuration.isVerbose();
    	monitorURL = buildMonitorURL(configuration.getBaseUrl(), configuration.getApplicationID(), serverID);
    	timeSyncURL = buildTimeSyncURL(configuration.getBaseUrl());
    	sslTrustManager = configuration.getSslTrustManager();
    }

	// *** public methods ***

    // sends a status check request and returns a status response
    public StatusResponse sendStatusRequest() {
    	return (StatusResponse)sendRequest(RequestType.STATUS, monitorURL, null, null, "GET");
    }

    // sends a beacon send request and returns a status response
    public StatusResponse sendBeaconRequest(String clientIPAddress, byte[] data) {
		return (StatusResponse)sendRequest(RequestType.BEACON, monitorURL, clientIPAddress, data, "POST");
    }

    // sends a time sync request and returns a time sync response
    public TimeSyncResponse sendTimeSyncRequest() {
		return (TimeSyncResponse)sendRequest(RequestType.TIMESYNC, timeSyncURL, null, null, "GET");
    }

	// *** protected methods ***

    // generic request send with some verbose output and exception handling
    // protected because it's overridden by the TestHTTPClient
    protected Response sendRequest(RequestType requestType, String url, String clientIPAddress, byte[] data, String method) {
    	try {
    		if (verbose) {
    			System.out.println("HTTP " + requestType.getRequestName() + " Request: " + url);
    		}
    		return sendRequestInternal(requestType, url, clientIPAddress, data, method);
		} catch (Exception e) {
			if (verbose) {
				System.err.println("ERROR: " + requestType.getRequestName() + " Request failed!");
				e.printStackTrace();
			}
		}
    	return null;
    }

	// *** private methods ***

    // generic internal request send
    private Response sendRequestInternal(RequestType requestType, String url, String clientIPAddress, byte[] data, String method) throws IOException, GeneralSecurityException {
    	int retry = 1;
		while (true) {
			try {
				URL httpURL = new URL(url);
				HttpURLConnection connection = (HttpURLConnection)httpURL.openConnection();

				// specific handling for HTTPS
				if (connection instanceof HttpsURLConnection) {
					applySSLTrustManager((HttpsURLConnection) connection);
				}

				if (clientIPAddress != null) {
					connection.addRequestProperty("X-Client-IP", clientIPAddress);
				}
				connection.setConnectTimeout(CONNECT_TIMEOUT);
				connection.setReadTimeout(READ_TIMEOUT);
				connection.setRequestMethod(method);

				// gzip beacon data, if available
				if ((data != null) && (data.length > 0)) {
					byte[] gzippedData = gzip(data);

					if (verbose) {
						String decodedData = "";
						try {
							decodedData = new String(data, Beacon.CHARSET);
						} catch (UnsupportedEncodingException e) {
							// must not happen, as UTF-8 should *really* be supported
						}

						System.out.println("Beacon Payload: " + decodedData);
					}

					connection.setRequestProperty("Content-Encoding", "gzip");
					connection.setRequestProperty("Content-Length", String.valueOf(data.length));
					connection.setDoOutput(true);
					OutputStream outputStream = connection.getOutputStream();
					outputStream.write(gzippedData);
					outputStream.close();
				}

                return handleResponse(connection);


            } catch (IOException exception) {
				retry++;
				if (retry > MAX_SEND_RETRIES) {
					throw exception;
				}

				try {
					Thread.sleep(RETRY_SLEEP_TIME);
				} catch (InterruptedException e) {
				}
			}
		}
    }

    private Response handleResponse(HttpURLConnection connection) throws IOException {
        // get response code
        int responseCode = connection.getResponseCode();

        // check response code
        if (responseCode >= 400) {
            // process error

            // read error response
            String response = readResponse(connection.getErrorStream()); // input stream is closed in readResponse

            if (verbose) {
                System.out.println("HTTP Response: " + response);
                System.out.println("HTTP Response Code: " + responseCode);
            }

            // return null if error occurred
            return null;

        } else {
            // process status response

            // reading HTTP response
            String response = readResponse(connection.getInputStream()); // input stream is closed in readResponse

            if (verbose) {
                System.out.println("HTTP Response: " + response);
                System.out.println("HTTP Response Code: " + responseCode);
            }

            // create typed response based on response content
            if (response.startsWith(REQUEST_TYPE_TIMESYNC)) {
                return new TimeSyncResponse(response, responseCode);
            } else if (response.startsWith(REQUEST_TYPE_MOBILE)) {
                return new StatusResponse(response, responseCode);
            } else {
                return null;
            }
        }
    }

    private void applySSLTrustManager(HttpsURLConnection connection) throws NoSuchAlgorithmException, KeyManagementException {
        HttpsURLConnection httpsConnection = connection;

        SSLContext context = SSLContext.getInstance("TLS");
        X509TrustManager x509TrustManager;
        if (sslTrustManager == null || sslTrustManager.getX509TrustManager() == null) {
            // if provided trust manager is null use a strict one by default
            x509TrustManager = new SSLStrictTrustManager().getX509TrustManager();
        } else {
            x509TrustManager = sslTrustManager.getX509TrustManager();
        }
        context.init(null, new TrustManager[] { x509TrustManager }, new SecureRandom());
        httpsConnection.setSSLSocketFactory(context.getSocketFactory());

        if (sslTrustManager != null && sslTrustManager.getHostnameVerifier() != null) {
            httpsConnection.setHostnameVerifier(sslTrustManager.getHostnameVerifier());
        }
    }

    // build URL used for status check and beacon send requests
    private String buildMonitorURL(String baseURL, String applicationID, int serverID) {
		StringBuilder monitorURLBuilder = new StringBuilder();

		monitorURLBuilder.append(baseURL);
		monitorURLBuilder.append('?');
		monitorURLBuilder.append(REQUEST_TYPE_MOBILE);

		appendQueryParam(monitorURLBuilder, QUERY_KEY_SERVER_ID, Integer.toString(serverID));
    	appendQueryParam(monitorURLBuilder, QUERY_KEY_APPLICATION, applicationID);
    	appendQueryParam(monitorURLBuilder, QUERY_KEY_VERSION, Beacon.OPENKIT_VERSION);
    	appendQueryParam(monitorURLBuilder, QUERY_KEY_PLATFORM_TYPE, PLATFORM_TYPE_OPENKIT);

    	return monitorURLBuilder.toString();
	}

	// build URL used for time sync requests
	private String buildTimeSyncURL(String baseURL) {
		StringBuilder timeSyncURLBuilder = new StringBuilder();

		timeSyncURLBuilder.append(baseURL);
		timeSyncURLBuilder.append('?');
		timeSyncURLBuilder.append(REQUEST_TYPE_TIMESYNC);

    	return timeSyncURLBuilder.toString();
	}

	// helper method for appending query parameters
	private void appendQueryParam(StringBuilder urlBuilder, String key, String value) {
		String encodedValue = "";
		try {
			encodedValue = URLEncoder.encode(value, Beacon.CHARSET);
		} catch (UnsupportedEncodingException e) {
			// must not happen, as UTF-8 should *really* be supported
		}

		urlBuilder.append('&');
		urlBuilder.append(key);
		urlBuilder.append('=');
		urlBuilder.append(encodedValue);
    }

	// helper method for gzipping beacon data
	private static byte[] gzip(byte[] data) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream);
		gzipOutputStream.write(data);
		gzipOutputStream.close();
		byteArrayOutputStream.close();
		return byteArrayOutputStream.toByteArray();
	}

	// *** getter methods ***

	public int getServerID() {
		return serverID;
	}

	private static String readResponse(InputStream inputStream) throws IOException {
		StringBuilder responseBuilder = new StringBuilder();

		// reading HTTP response
		try {
			byte[] buffer = new byte[1024];
			int length = 0;
			while((length = inputStream.read(buffer)) > 0) {
				responseBuilder.append(new String(buffer, 0, length, Beacon.CHARSET));
			}
		} finally {
			inputStream.close();
		}

		return responseBuilder.toString();
	}

}
