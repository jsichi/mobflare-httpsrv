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

import java.io.*;

import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.server.handler.*;

public class Main<T> {
    public static void main(String[] args) throws Exception
    {
        if (args.length != 1) {
            System.err.println("Please specify the port number");
            return;
        }
        Integer port;
        try {
            port = Integer.parseInt(args[0]);
        } catch(NumberFormatException ex) {
            System.err.println("port_number must be a number");
            return;
        }
                	
        Server server = new Server(port);


        HandlerCollection handlers = new HandlerCollection();
        ServletContextHandler context =
            new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        handlers.setHandlers(
            new Handler[]{context,new DefaultHandler(),requestLogHandler});
        server.setHandler(handlers);
        NCSARequestLog requestLog =
            new NCSARequestLog("./logs/jetty-yyyy_mm_dd.request.log");
        requestLog.setRetainDays(90);
        requestLog.setAppend(true);
        requestLog.setExtended(true);
        requestLog.setLogTimeZone("GMT");
        requestLogHandler.setRequestLog(requestLog);
        
	// mobflare server
        context.addServlet(new ServletHolder(new FlareServlet()), "/mobflare/*");

        // start the server
        server.start();
        server.join();		
    }
	
    public T get()
    {
        return null;
    }
}
