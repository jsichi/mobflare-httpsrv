/**
 * Copyright 2012 Lingering Socket Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lingeringsocket.mobflare.httpsrv;

import org.eclipse.jetty.util.*;
import org.eclipse.jetty.util.ajax.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.servlet.*;
import javax.servlet.http.*;

public class FlareServlet extends HttpServlet
{
    private static long ONE_DAY_IN_MILLIS = 1000L * 60L * 60L * 24L;
    private static double EARTH_RADIUS_KM = 6371;
    private static int MIN_CLIENT_VERSION = 3;

    private JSON jsonProc;
    private ConcurrentHashMap<String, Flare> flaresByName;
    private Timer timer;

    FlareServlet()
    {
	jsonProc = new JSON();
	flaresByName = new ConcurrentHashMap<String, Flare>();
        timer = new Timer();

        // purge flares every five seconds
        timer.scheduleAtFixedRate(new PurgeTask(), 5000, 5000);
    }

    private String decodeUrlString(String url) 
    {
        return UrlEncoded.decodeString(url, 0, url.length(), "UTF-8");
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException
    {
	String[] parts = req.getRequestURI().split("/");
        if (parts.length < 2) {
            // unknown resource
            resp.sendError(404);
            return;
        }
        String parent = parts[parts.length - 2];
	String key = parts[parts.length - 1];
        if (parent.equals("mobflare")) {
            if (key.equals("list")) {
                doGetList(parseQueryString(req), resp);
                return;
            }
        } else if (parent.equals("flare")) {
            doGetFlare(decodeUrlString(key), resp);
            return;
        }

        // unknown resource
        resp.sendError(404);
    }

    private MultiMap<String> parseQueryString(HttpServletRequest req)
    {
        MultiMap<String> params = new MultiMap<String>();
        String queryString = req.getQueryString();
        if (queryString != null) {
            UrlEncoded.decodeTo(queryString, params, "UTF-8");
        }
        return params;
    }

    private void doGetFlare(String flareName, HttpServletResponse resp)
        throws ServletException, IOException 
    {
        Flare flare = flaresByName.get(flareName);
        if (flare == null) {
            resp.sendError(404);
            return;
        }
        Map<String, Object> params = new HashMap<String, Object>();
        synchronized (flare) {
            params.put("name", flareName);
            params.put("quorumSize", flare.quorumSize);
            params.put("joinCount", flare.joinCount);
            params.put("repeatDeciSeconds", flare.repeatDeciSeconds);
            params.put("countdownSeconds", flare.countdownSeconds);
            params.put("staggerDeciSeconds", flare.staggerDeciSeconds);
            params.put("countdownStartTime", flare.countdownStartTime);
            params.put("latitude", flare.latitude);
            params.put("longitude", flare.longitude);
        }

	String json = jsonProc.toJSON(params);
	resp.getOutputStream().write(json.getBytes());
    }
    
    private void doGetList(MultiMap<String> params, HttpServletResponse resp)
        throws ServletException, IOException 
    {
        long now = System.currentTimeMillis();

        int clientVersion = readIntParam(
            params, "clientVersion", MIN_CLIENT_VERSION);
        if (clientVersion < MIN_CLIENT_VERSION) {
            // forbidden version
            resp.sendError(403);
            return;
        }

        double longitude = readDoubleParam(params, "longitude", 0);
        double latitude = readDoubleParam(params, "latitude", 0);
        double radius = readDoubleParam(params,"radius", 100000);
        double latitudeSin = Math.sin(latitude);
        double latitudeCos = Math.cos(latitude);

        // maybe we should be using a better JSON library to avoid this
        StringWriter sw = new StringWriter();
        sw.append("[");
        List<String> flareNames = new ArrayList<String>();
        boolean comma = false;
        for (Map.Entry<String, Flare> entry : flaresByName.entrySet()) {
            Flare flare = entry.getValue();
            synchronized (flare) {
                if (isExpired(entry.getValue(), now)) {
                    continue;
                }
            }
            
            double dist = EARTH_RADIUS_KM * Math.acos(
                latitudeSin * flare.latitudeSin
                + (latitudeCos * flare.latitudeCos
                    * Math.cos(flare.longitude - longitude)));
            if (dist > radius) {
                continue;
            }
            if (comma) {
                sw.append(",");
            } else {
                comma = true;
            }
            sw.append("{\"name\":\"");
            sw.append(
                flare.name.replace("\\","\\\\").replace("\"","\\\""));
            sw.append("\",\"km\":");
            sw.append(Double.toString(dist));
            sw.append("}");
        }
        sw.append("]");

	String json = sw.toString();
        
	resp.getOutputStream().write(json.getBytes());
    }
    
    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException
    {
	String[] parts = req.getRequestURI().split("/");
	String key = decodeUrlString(parts[parts.length - 1]);
	byte[] body = readInputStream(req.getInputStream());
	String json = new String(body);
        Map<String, Object> params =
            (Map<String, Object>) jsonProc.fromJSON(json);
	Flare flare = new Flare();
	flare.name = key;
        flare.creationTime = System.currentTimeMillis();
	flare.quorumSize = readIntParam(params, "quorumSize", 1);
	flare.countdownSeconds = readIntParam(params, "countdownSeconds", 0);
	flare.repeatDeciSeconds = readIntParam(params, "repeatDeciSeconds", 0);
	flare.staggerDeciSeconds = readIntParam(params, "staggerDeciSeconds", 0);
	flare.latitude = readDoubleParam(params, "latitude", 0);
	flare.longitude = readDoubleParam(params, "longitude", 0);
        flare.latitudeSin = Math.sin(flare.latitude);
        flare.latitudeCos = Math.cos(flare.latitude);
        if ((flare.staggerDeciSeconds != 0)
            && (flare.repeatDeciSeconds != 0))
        {
            flare.repeatDeciSeconds =
                flare.staggerDeciSeconds * flare.quorumSize;
        }
	flare.joinCount = 0;
	Flare oldFlare = flaresByName.putIfAbsent(key, flare);
        if (oldFlare != null) {
            // name conflict; not really HTTP-kosher
            resp.sendError(409);
            return;
        }

        params = new HashMap<String, Object>();
        params.put("name", key);
        
	json = jsonProc.toJSON(params);
	resp.getOutputStream().write(json.getBytes());
    }

    private int readIntParam(
        Map<String, Object> params, String name, int defaultVal)
    {
        Object obj = params.get(name);
        if (obj == null) {
            return defaultVal;
        }
        if (obj instanceof Number) {
            Number val = (Number) params.get(name);
            return val.intValue();
        } else {
            return Integer.valueOf(obj.toString());
        }
    }

    private double readDoubleParam(
        Map<String, Object> params, String name, double defaultVal)
    {
        Object obj = params.get(name);
        if (obj == null) {
            return defaultVal;
        }
        if (obj instanceof Number) {
            Number val = (Number) params.get(name);
            return val.doubleValue();
        } else {
            return Double.valueOf(obj.toString());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException
    {
	// method not allowed
	resp.sendError(405);
    }
	
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException
    {
	String[] parts = req.getRequestURI().split("/");
	String key = decodeUrlString(parts[parts.length - 1]);

        Flare flare = flaresByName.get(key);
        if (flare == null) {
            resp.sendError(404);
            return;
        }

        int participantNumber;
        synchronized (flare) {
            if ((flare.countdownStartTime != 0)
                && (flare.repeatDeciSeconds != 0)
                && (flare.staggerDeciSeconds != 0))
            {
                // for a cyclic ripple flare, it is illegal to attempt to
                // join after the countdown starts
                resp.sendError(404);
                return;
            }
            participantNumber = flare.joinCount;
            flare.joinCount++;
            if (flare.joinCount == flare.quorumSize) {
                startCountdown(flare);
            }
        }
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("participantNumber", participantNumber);

	String json = jsonProc.toJSON(params);
	resp.getOutputStream().write(json.getBytes());
    }

    private void startCountdown(Flare flare) 
    {
        // round down to discard any milliseconds
        long t = System.currentTimeMillis();
        long r = (t % 1000);
        t -= r;
        flare.countdownStartTime = t;
    }
    
    private byte[] readInputStream(InputStream is) throws IOException
    {
	ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	int nRead;
	byte[] data = new byte[16384];

	while ((nRead = is.read(data, 0, data.length)) != -1) {
	    buffer.write(data, 0, nRead);
	}
	buffer.flush();
	return buffer.toByteArray();
    }

    private boolean isExpired(Flare flare, long now)
    {
        if (flare.countdownStartTime != 0) {
            
            long countdownEndTime = flare.countdownStartTime
                + flare.countdownSeconds * 1000;
            if (now > countdownEndTime) {
                return true;
            }
        }
        if (now > flare.creationTime + ONE_DAY_IN_MILLIS) {
            return true;
        }
        return false;
    }
    
    class PurgeTask extends TimerTask 
    {
        public void run() 
        {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Flare>> it =
                flaresByName.entrySet().iterator();
            while (it.hasNext()) {
                Flare flare = it.next().getValue();
                boolean expired;
                synchronized (flare) {
                    expired = isExpired(flare, now);
                }
                if (expired) {
                    it.remove();
                }
            }
        }
    }
}
