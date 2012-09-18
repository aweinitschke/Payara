/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.admin.cli.remote;

import com.sun.appserv.management.client.prefs.LoginInfo;
import com.sun.appserv.management.client.prefs.LoginInfoStore;
import com.sun.appserv.management.client.prefs.LoginInfoStoreFactory;
import com.sun.appserv.management.client.prefs.StoreException;
import com.sun.enterprise.admin.cli.CLICommand;
import com.sun.enterprise.admin.cli.DirectoryClassLoader;
import com.sun.enterprise.admin.cli.Environment;
import com.sun.enterprise.admin.cli.ProgramOptions;
import com.sun.enterprise.admin.cli.ProgramOptions.PasswordLocation;
import com.sun.enterprise.admin.progress.CommandProgressImpl;
import com.sun.enterprise.admin.remote.RemoteRestAdminCommand;
import com.sun.enterprise.admin.util.CachedCommandModel;
import com.sun.enterprise.admin.util.CommandModelData;
import com.sun.enterprise.admin.util.CommandModelData.ParamModelData;
import com.sun.enterprise.module.ModulesRegistry;
import com.sun.enterprise.module.single.StaticModulesRegistry;
import com.sun.enterprise.security.store.AsadminSecurityUtil;
import com.sun.enterprise.universal.i18n.LocalStringsImpl;
import com.sun.enterprise.util.SystemPropertyConstants;
import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import org.glassfish.api.ActionReport;
import org.glassfish.api.ActionReport.ExitCode;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandProgress;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.common.util.admin.ManPageFinder;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.BuilderHelper;

/**
 * A remote command handled by the asadmin CLI.
 */
public class RemoteCLICommand extends CLICommand {

    private static final LocalStringsImpl   strings =
            new LocalStringsImpl(RemoteCommand.class);

    // return output string rather than printing it
    private boolean                     returnOutput = false;
    private String                      output;
    private boolean                     returnAttributes = false;
    private ActionReport                ar;
    private String                      usage;

    private String                      responseFormatType;
    private OutputStream                userOut;
    private File                        outputDir;

    private RemoteCLICommand.CLIRemoteAdminCommand       rac;

    /**
     * A special RemoteAdminCommand that overrides methods so that
     * we can handle the interactive requirements of a CLI command.
     */
    private class CLIRemoteAdminCommand extends RemoteRestAdminCommand {

        private static final String JSESSIONID  = "JSESSIONID";
        private static final String COOKIE_HEADER  = "Cookie";
        private CookieManager cookieManager = null;
        private File sessionCache = null;
        private final ProgressStatusPrinter statusPrinter;

        /**
         * Construct a new remote command object.  The command and arguments
         * are supplied later using the execute method in the superclass.
         */
        public CLIRemoteAdminCommand(String name, String host, int port,
                boolean secure, String user, String password, Logger logger,
                String authToken)
                throws CommandException {
            super(name, host, port, secure, user, password, logger, getCommandScope(),
                    authToken, true /* prohibitDirectoryUploads */);

            StringBuilder sessionFilePath = new StringBuilder();

            // Store the cache at: $GFCLIENT/cache/{host}_{port}/session
            sessionFilePath.append("cache").append(File.separator);
            sessionFilePath.append(host).append("_");
            sessionFilePath.append(port).append(File.separator);
            sessionFilePath.append("session");

            sessionCache = new File(AsadminSecurityUtil.getDefaultClientDir(),
                    sessionFilePath.toString());
            statusPrinter = new ProgressStatusPrinter(env.debug() || env.trace(), logger);
            if (!env.getBooleanOption(ProgramOptions.TERSE)) {
                super.registerListener(CommandProgress.EVENT_PROGRESSSTATUS_CHANGE, statusPrinter);
                super.registerListener(CommandProgress.EVENT_PROGRESSSTATUS_STATE, statusPrinter);
            }
        }
        
        @Override
        public void fetchCommandModel() throws CommandException {
            super.fetchCommandModel();
        }

        /**
         * If we're interactive, prompt for a new username and password.
         * Return true if we're successful in collecting new information
         * (and thus the caller should try the request again).
         */
        @Override
        protected boolean updateAuthentication() {
            Console cons;
            if (programOpts.isInteractive() &&
                    (cons = System.console()) != null) {
                // if appropriate, tell the user why authentication failed
                PasswordLocation pwloc = programOpts.getPasswordLocation();
                if (pwloc == PasswordLocation.PASSWORD_FILE) {
                    logger.fine(strings.get("BadPasswordFromFile",
                                                programOpts.getPasswordFile()));
                } else if (pwloc == PasswordLocation.LOGIN_FILE) {
                    try {
                        LoginInfoStore store =
                            LoginInfoStoreFactory.getDefaultStore();
                        logger.fine(strings.get("BadPasswordFromLogin",
                                        store.getName()));
                    } catch (StoreException ex) {
                        // ignore it
                    }
                }

                String user = null;
                // only prompt for a user name if the user name is set to
                // the default.  otherwise, assume the user specified the
                // correct username to begin with and all we need is the
                // password.
                if (programOpts.getUser() == null) {
                    cons.printf("%s ", strings.get("AdminUserPrompt"));
                    user = cons.readLine();
                    if (user == null)
                        return false;
                }
                String password;
                String puser = ok(user) ? user : programOpts.getUser();
                if (ok(puser))
                    password = readPassword(
                                strings.get("AdminUserPasswordPrompt", puser));
                else
                    password = readPassword(strings.get("AdminPasswordPrompt"));
                if (password == null)
                    return false;
                if (ok(user)) {      // if none entered, don't change
                    programOpts.setUser(user);
                    this.user = user;
                }
                programOpts.setPassword(password, PasswordLocation.USER);
                this.password = password;
                return true;
            }
            return false;
        }

        /**
         * Get from environment.
         */
        @Override
        protected String getFromEnvironment(String name) {
            return env.getStringOption(name);
        }

        /**
         * Called when a non-secure connection attempt fails and it appears
         * that the server requires a secure connection.
         * Tell the user that we're retrying.
         */
        @Override
        protected boolean retryUsingSecureConnection(String host, int port) {
            String msg = strings.get("ServerMaybeSecure", host, port + "");
            logger.info(msg);
            return true;
        }

        /**
         * Return the error message to be used in the AuthenticationException.
         * Subclasses can override to provide a more detailed message, for
         * example, indicating the source of the password that failed.
         */
        @Override
        protected String reportAuthenticationException() {
            String msg = null;
            PasswordLocation pwloc =
                programOpts.getPasswordLocation();
            if (pwloc == PasswordLocation.PASSWORD_FILE) {
                msg = strings.get("InvalidCredentialsFromFile",
                                    programOpts.getUser(),
                                    programOpts.getPasswordFile());
            } else if (pwloc == PasswordLocation.LOGIN_FILE) {
                try {
                    LoginInfoStore store =
                        LoginInfoStoreFactory.getDefaultStore();
                    msg = strings.get("InvalidCredentialsFromLogin",
                                        programOpts.getUser(),
                                        store.getName());
                } catch (StoreException ex) {
                    // ignore it
                }
            }

            if (msg == null)
                msg = strings.get("InvalidCredentials", programOpts.getUser());
            return msg;
        }

        /**
         * Adds cookies to the header to support session based client 
         * routing.
         *
         *
         */
        @Override
        protected synchronized Invocation.Builder addAdditionalHeaders(final Invocation.Builder request) {
            return addCookieHeaders(request);
        }
        
        @Override
        protected boolean useSse() throws CommandException {
            return programOpts.isDetachedCommand() || super.useSse();
        }
        
//        @Override
//        protected boolean refetchInvalidModel() {
//            return false;
//        }

        /*
         * Adds any cookies maintained in the clients session cookie cache.
         */
        private Invocation.Builder addCookieHeaders(final Invocation.Builder request) {

            // Get the last modified time of the session cache file.
            long modifiedTime = sessionCache.lastModified();
            if (modifiedTime == 0) {
                // No session file so no cookies to add.
                return request;
            }

            // Remote any Set-Cookie's in the system cookie manager otherwise
            // they appear as cookies in the outgoing request.
            ((CookieManager)CookieHandler.getDefault()).getCookieStore().removeAll();

            cookieManager = new CookieManager(
                                new ClientCookieStore(
                                        new CookieManager().getCookieStore(), 
                                        sessionCache),
                                CookiePolicy.ACCEPT_ALL);

            // XXX: If this is an interactive command we don't want to 
            // keep reloading the cookie store.
            try {
                ((ClientCookieStore) cookieManager.getCookieStore()).load();
            } catch (IOException e) {
                logger.log(Level.FINER, "Unable to load cookies: {0}", e.toString());
                return request;
            }

            if (isSessionCookieExpired(cookieManager, modifiedTime)) {
                logger.finer("Cookie session file has expired.");
                if (!sessionCache.delete()) {
                    logger.finer("Unable to delete session file.");
                }
                return request;
            }

            StringBuilder sb = new StringBuilder("$Version=1");
            boolean hasCookies = false;
            for (HttpCookie cookie: cookieManager.getCookieStore().getCookies()) {
                hasCookies = true;
                sb.append("; ").append(cookie.getName()).append("=").append(cookie.getValue());
            }
            if (hasCookies) {
                return request.header(COOKIE_HEADER, sb.toString());
            } 
            return request;
        }

        /*
         * Looks for the SESSIONID cookie in the cookie store and 
         * determines if the cookie has expired (based on the
         * Max-Age and the time in which the file was last modified.)
         * The assumption, based on how we write cookies, is that
         * the cookie session file will only be changed when the
         * JSESSIONID cookie changes.   Therefor the last mod time of
         * the file is a reasonable proxy for when the cookie was
         * "created".
         * If we can't find the JSESSIONID cookie then we return true.
         */
        private boolean isSessionCookieExpired(CookieManager manager, 
                long creationTime) {
            for (URI uri: manager.getCookieStore().getURIs()) {
                for (HttpCookie cookie: manager.getCookieStore().get(uri)) {
                    if (cookie.getName().equals(JSESSIONID)) {
                        if ((creationTime / 1000 + cookie.getMaxAge()) < 
                                   System.currentTimeMillis()/1000) {
                            return true;
                        } else
                            return false;
                    }
                }
            }
            return true;
        }

        /**
         * Processes the headers to support session based client 
         * routing.
         *
         */
        @Override
        protected synchronized void processHeaders(final Response response) {
            processCookieHeaders(response);
        }

        private void processCookieHeaders(final Response response) {
            Map<String, NewCookie> cookies = response.getCookies();

//            CookieManager systemCookieManager = (CookieManager)CookieManager.getDefault();
//
//            if (systemCookieManager == null) {
//                logger.finer("Assertion failed: null system CookieManager");
//                return;
//            }
//
//            // Using the system CookieHandler, retrieve any cookies.
//            CookieStore systemCookieJar = systemCookieManager.getCookieStore();
//            List<HttpCookie> newCookies = systemCookieJar.getCookies();

            if (cookies == null || cookies.isEmpty()) {
                // If there are no cookies to set in the request we
                // have nothing to do.
                return;
            }

               
            /*
            Console console = System.console();
            for (HttpCookie cookie: newCookies) {
                console.printf("Cookie: %s%n", cookie.toString());
                console.printf("   MaxAge: %d%n", cookie.getMaxAge());
                console.printf("   Domain: %s%n", cookie.getDomain());
                console.printf("   Path: %s%n", cookie.getPath());
            }
             * 
             */

            // Get the last modified time of the session cache file.
            if (sessionCache.lastModified() == 0) {
                // No file, if we have cookies we need to save them.
                if (cookieManager == null) {
                    cookieManager = new CookieManager(
                            new ClientCookieStore(
                                    new CookieManager().getCookieStore(), 
                                    sessionCache),
                            CookiePolicy.ACCEPT_ALL);
                }
                try {
                    MultivaluedMap metadata = response.getMetadata();
                    cookieManager.put(((ClientCookieStore)cookieManager.getCookieStore()).getStaticURI(),
                            metadata);
                } catch (IOException e) {
                    // Thrown by cookieManger.put()
                    logger.finer("Unable to save cookies: " + e.toString());
                    return;
                }

                try {
                    ((ClientCookieStore) cookieManager.getCookieStore()).store();
                } catch (IOException e) {
                    logger.finer("Unable to store cookies: " + e.toString());
                }
                return;
            }

            if (cookieManager == null) {
                cookieManager = new CookieManager(
                                    new ClientCookieStore(
                                        new CookieManager().getCookieStore(), 
                                        sessionCache),
                                    CookiePolicy.ACCEPT_ALL);
                try {
                    ((ClientCookieStore) cookieManager.getCookieStore()).load();
                } catch (IOException e) {
                    logger.finer("Unable to load cookies: " + e.toString());
                    return;
                }
            }

            boolean newCookieFound = false;

            // Check to see if any of the set cookies in the reply are
            // different from what is already in the persistent store.
            for (NewCookie nc: cookies.values()) {
                // Check to see if any of the set cookies in the reply are
                // different from what is already in the persistent store.
                HttpCookie hcookie = null;
                for (HttpCookie hc : cookieManager.getCookieStore().getCookies()) {
                    if (equalsStrIgnoreCase(hc.getName(), nc.getName()) &&
                        equalsStrIgnoreCase(hc.getDomain(), nc.getDomain()) &&
                        equalsStr(hc.getPath(), nc.getPath())) {
                        hcookie = hc;
                    }
                }
                if (hcookie == null) {
                    newCookieFound = true;
                    break;
                } else {
                    if (!nc.getValue().equals(hcookie.getValue())) {
                        newCookieFound = true;
                        break;
                    }
                }
            }

            // Note: This has the potential to overwrite the existing file
            // which may contain changes that were introduced by another
            // command's execution.   Those changes will be lost.
            // Since the cookie store is only used for optimized session
            // routing we are only interested in preserving the last
            // set of session/routing cookies received from the server/LB
            // as we believe those to be the most current and accurate in
            // regards to future request routing.
            if (newCookieFound) {
                try {
                    try {
                        MultivaluedMap metadata = response.getMetadata();
                        cookieManager.put(((ClientCookieStore)cookieManager.getCookieStore()).getStaticURI(),
                            metadata);
                    } catch (IOException e) {
                        // Thrown by cookieManger.put()
                        logger.finer("Unable to save cookies: " + e.toString());
                        return;
                    }
                    ((ClientCookieStore) cookieManager.getCookieStore()).store();
                } catch (IOException e) {
                    logger.finer("Unable to store cookies: " + e.toString());
                }
            } else {
                // No cookies changed.  Update the modification time on the store.
                ((ClientCookieStore) cookieManager.getCookieStore()).touchStore();
            }
        }
        
    }

    /**
     * A class loader for the "modules" directory.
     */
    private static ClassLoader moduleClassLoader;

    /**
     * A habitat just for finding man pages.
     */
    private static ServiceLocator manHabitat;

    /**
     * Construct a new remote command object.  The command and arguments
     * are supplied later using the execute method in the superclass.
     */
    public RemoteCLICommand() throws CommandException {
        super();
    }

    /**
     * Construct a new remote command object.  The command and arguments
     * are supplied later using the execute method in the superclass.
     */
    public RemoteCLICommand(String name, ProgramOptions po, Environment env)
            throws CommandException {
        super(name, po, env);
    }

    /**
     * Construct a new remote command object.  The command and arguments
     * are supplied later using the execute method in the superclass.
     * This variant is used by the RemoteDeploymentFacility class to
     * control and capture the output.
     */
    public RemoteCLICommand(String name, ProgramOptions po, Environment env,
            String responseFormatType, OutputStream userOut)
            throws CommandException {
        this(name, po, env);
        this.responseFormatType = responseFormatType;
        this.userOut = userOut;
    }
    
    /** Helper for situation, where {@code CommandModel} is from cache and
     * something shows, that server side signature of command was changed
     */
    private void reExecuteAfterMetadataUpdate() throws ReExecuted, CommandException {
        //Check CommandModel
        if (rac == null) {
            return;
        }
        if (rac.getCommandModel() == null) {
            return;
        }
        if (!rac.isCommandModelFromCache()) {
            return;
        }
        //Refetch command model
        String eTag = CachedCommandModel.computeETag(rac.getCommandModel());
        rac = null;
        initializeRemoteAdminCommand();
        rac.fetchCommandModel();
        String newETag = CachedCommandModel.computeETag(rac.getCommandModel());
        if (eTag != null && eTag.equals(newETag)) {
            return; //Nothing change in command model
        }
        logger.log(Level.WARNING, "Command signature of {0} command was changed. Re executing with new metadata.", name);
        //clean state of this instance
        this.options = null;
        this.operands = null;
        //Reexecute it
        int result = execute(argv);
        throw new ReExecuted(result);
    }
    
    @Override
    public int execute(String... argv) throws CommandException {
        try {
            logger.log(Level.FINEST, "RemoteCLICommand executes");
            return super.execute(argv);
        } catch (ReExecuted reex) {
            return reex.getExecutionResult();
        }
    }

    /**
     * Set the directory in which any returned files will be stored.
     * The default is the user's home directory.
     */
    public void setFileOutputDirectory(File dir) {
        outputDir = dir;
    }

    @Override
    protected void prepare()
            throws CommandException, CommandValidationException  {
        try {
            processProgramOptions();

            initializeAuth();

            /*
             * Now we have all the information we need to create
             * the remote admin command object.
             */
            initializeRemoteAdminCommand();

            if (responseFormatType != null) {
                rac.setResponseFormatType(responseFormatType);
            }
            if (userOut != null) {
                rac.setUserOut(userOut);
            }

            /*
             * Initialize a CookieManager so that we can retreive
             * any cookies included in the reply.   These cookies
             * (e.g. JSESSIONID, JROUTE) are used for CLI session
             * based routing.
             */
            initializeCookieManager();

            /*
             * If this is a help request, we don't need the command
             * metadata and we throw away all the other options and
             * fake everything else.
             */
            if (programOpts.isHelp()) {
                CommandModelData cm = new CommandModelData(name);
                cm.add(new ParamModelData("help", boolean.class, true, "false", "?"));
                this.commandModel = cm;
                rac.setCommandModel(cm);
                return;
            }

            /*
             * Find the metadata for the command.
             */
            commandModel = rac.getCommandModel();
        } catch (CommandException cex) {
            logger.finer("RemoteCommand.prepare throws " + cex);
            throw cex;
        } catch (Exception e) {
            logger.finer("RemoteCommand.prepare throws " + e);
            throw new CommandException(e.getMessage());
        }
    }
    
    @Override
    protected void prevalidate() throws CommandException {
        try {
            super.prevalidate();
        } catch (CommandException ex) {
            reExecuteAfterMetadataUpdate();
            throw ex;
        }
    }
    
    @Override
    protected void parse() throws CommandException {
        try {
            super.parse();
        } catch (CommandValidationException ex) {
            reExecuteAfterMetadataUpdate();
            throw ex;
        }
    }

    /**
     * If it's a help request, don't prompt for any missing options.
     */
    @Override
    protected void validate()
            throws CommandException, CommandValidationException  {
        if (programOpts.isHelp()) {
            return;
        }
        try {
            super.validate();
        } catch (CommandValidationException ex) {
            reExecuteAfterMetadataUpdate();
            throw ex;
        }
    }
    
    @Override
    protected void inject() throws CommandException {
        try {
            super.prevalidate();
        } catch (CommandValidationException ex) {
            reExecuteAfterMetadataUpdate();
            throw ex;
        }
    }
    
//    /**
//     * We do all our help processing in executeCommand.
//     */
//    @Override
//    protected boolean checkHelp()
//            throws CommandException, CommandValidationException {
//        return false;
//    }

    /**
     * Runs the command using the specified arguments.
     */
    @Override
    protected int executeCommand()
            throws CommandException, CommandValidationException {
        try {
            rac.statusPrinter.reset();
            options.set("DEFAULT", operands);
            if (programOpts.isDetachedCommand())  {
                rac.registerListener("AdminCommandInstance\\.stateChanged", new DetachListener(logger, rac));

            }
            output = rac.executeCommand(options);
            ar = rac.getActionReport();
            if (!returnAttributes && !returnOutput) {
                if (output.length() > 0) {
                    logger.info(output);
                }
            }
        } catch (CommandValidationException cve) {
            reExecuteAfterMetadataUpdate();
            throw cve;
        } catch (CommandException ex) {
            // if a --help request failed, try to emulate it locally
            if (programOpts.isHelp()) {
                Reader r = getLocalManPage();
                BufferedReader br = null;
                try {
                    if (r != null) {
                        br = new BufferedReader(r);
                        PrintWriter pw = new PrintWriter(System.out);
                        char[] buf = new char[8192];
                        int cnt;
                        while ((cnt = br.read(buf)) > 0)
                            pw.write(buf, 0, cnt);
                        pw.flush();
                        return SUCCESS;
                    }
                } catch (IOException ioex2) {
                    // ignore it and throw original exception
                } finally {
                    try {
                        if (br != null)
                            br.close();
                    } catch (IOException ioex3) {
                        // ignore it
                    }
                }
            }
            throw ex;
        }
        ActionReport ar = rac.getActionReport();
        //logger.log(Level.INFO, Metrix.getInstance().toString());
        if (ar != null && ExitCode.WARNING == ar.getActionExitCode()) {
            return WARNING;
        }
        return SUCCESS;
    }


    /**
     * Execute the command and return the output as a string
     * instead of writing it out.
     */
    public String executeAndReturnOutput(String... args)
            throws CommandException, CommandValidationException {
        /*
         * Tell the low level output processing to just save the output
         * string instead of writing it out.  Yes, this is pretty gross.
         */
        returnOutput = true;
        execute(args);
        returnOutput = false;
        return output;
    }

//    /**
//     * Execute the command and return the main attributes from the manifest
//     * instead of writing out the output.
//     */
//    public Map<String, String> executeAndReturnAttributes(String... args)
//            throws CommandException, CommandValidationException {
//        /*
//         * Tell the low level output processing to just save the attributes
//         * instead of writing out the output.  Yes, this is pretty gross.
//         */
//        returnAttributes = true;
//        execute(args);
//        returnAttributes = false;
//        return attrs;
//    }

    /**
     * Get the usage text.
     * If we got usage information from the server, use it.
     *
     * @return usage text
     */
    @Override
    public String getUsage() {
        if (usage == null) {
            if (rac == null) {
                /*
                 * We weren't able to initialize the RemoteAdminCommand
                 * object, probably because we failed to parse the program
                 * options.  With no ability to contact the remote server,
                 * we can't provide any command-specific usage information.
                 * Sigh.
                 */
                return getCommandUsage();
            }
            usage = rac.getUsage();
        }
        if (usage == null)
            return super.getUsage();

        StringBuilder usageText = new StringBuilder();
        usageText.append(strings.get("Usage", getBriefCommandUsage()));
        usageText.append(" ");
        usageText.append(usage);
        return usageText.toString();
    }

    /**
     * Get the man page from the server.  If the man page isn't
     * available, e.g., because the server is down, try to find
     * it locally by looking in the modules directory.
     */
    @Override
    public BufferedReader getManPage() {
        try {
            initializeRemoteAdminCommand();
            String manpage = rac.getManPage();
            return new BufferedReader(new StringReader(manpage));
        } catch (CommandException cex) {
            // ignore
        }

        /*
         * Can't find the man page remotely, try to find it locally.
         * XXX - maybe should only do this on connection failure
         */
        BufferedReader r = getLocalManPage();
        return r != null ? r : super.getManPage();
    }

    /**
     * Try to find a local version of the man page for this command.
     */
    private BufferedReader getLocalManPage() {
        logger.fine(strings.get("NoRemoteManPage"));
        String cmdClass = getCommandClass(getName());
        ClassLoader mcl = getModuleClassLoader();
        if (cmdClass != null && mcl != null) {
            return ManPageFinder.getCommandManPage(getName(), cmdClass,
                                            Locale.getDefault(), mcl, logger);
        }
        return null;
    }

    private void initializeRemoteAdminCommand() throws CommandException {
        if (rac == null) {
            rac = new RemoteCLICommand.CLIRemoteAdminCommand(name,
                programOpts.getHost(), programOpts.getPort(),
                programOpts.isSecure(), programOpts.getUser(),
                programOpts.getPassword(), logger, programOpts.getAuthToken());
            rac.setFileOutputDirectory(outputDir);
            rac.setInteractive(programOpts.isInteractive());
            rac.setOmitCache(!programOpts.isUseCache()); //todo: [mmar] Remove after implementation CLI->ReST done
        }
    }

    private void initializeAuth() throws CommandException {
        LoginInfo li = null;

        try {
            LoginInfoStore store = LoginInfoStoreFactory.getDefaultStore();
            li = store.read(programOpts.getHost(), programOpts.getPort());
            if (li == null)
                return;
        } catch (StoreException se) {
            logger.finer(
                    "Login info could not be read from ~/.asadminpass file");
            return;
        }

        /*
         * If we don't have a user name, initialize it from .asadminpass.
         * In that case, also initialize the password unless it was
         * already specified (overriding what's in .asadminpass).
         *
         * If we already have a user name, and it's the same as what's
         * in .asadminpass, and we don't have a password, use the password
         * from .asadminpass.
         */
        if (programOpts.getUser() == null) {
            // not on command line and in .asadminpass
            logger.finer("Getting user name from ~/.asadminpass: " +
                                        li.getUser());
            programOpts.setUser(li.getUser());
            if (programOpts.getPassword() == null) {
                // not in passwordfile and in .asadminpass
                logger.finer(
                    "Getting password from ~/.asadminpass");
                programOpts.setPassword(li.getPassword(),
                    ProgramOptions.PasswordLocation.LOGIN_FILE);
            }
        } else if (programOpts.getUser().equals(li.getUser())) {
            if (programOpts.getPassword() == null) {
                // not in passwordfile and in .asadminpass
                logger.finer(
                    "Getting password from ~/.asadminpass");
                programOpts.setPassword(li.getPassword(),
                    ProgramOptions.PasswordLocation.LOGIN_FILE);
            }
        }
    }

    /*
     * Initialize a CookieManager so that we can retreive
     * any cookies included in the reply.   These cookies
     * (e.g. JSESSIONID, JROUTE) are used for CLI session
     * based routing.
    */
    private void initializeCookieManager() {
        CookieStore defaultCookieStore = new CookieManager().getCookieStore();
        CookieManager manager = new CookieManager(defaultCookieStore,
                CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(manager);
    }

    /**
     * Given a command name, return the name of the class that implements
     * that command in the server.
     */
    private static String getCommandClass(String cmdName) {
        ServiceLocator h = getManHabitat();
        String cname = "org.glassfish.api.admin.AdminCommand";
        ActiveDescriptor<?> ad = h.getBestDescriptor(
                BuilderHelper.createNameAndContractFilter(cname, cmdName));
        if (ad == null) return null;
        return ad.getImplementation();
    }

    /**
     * Return a Habitat used just for reading man pages from the
     * modules in the modules directory.
     */
    private static synchronized ServiceLocator getManHabitat() {
        if (manHabitat != null)
            return manHabitat;
        ModulesRegistry registry = new StaticModulesRegistry(getModuleClassLoader());
        manHabitat = registry.createServiceLocator("default");
        return manHabitat;
    }

    /**
     * Return a ClassLoader that loads classes from all the modules
     * (jar files) in the <INSTALL_ROOT>/modules directory.
     */
    private static synchronized ClassLoader getModuleClassLoader() {
        if (moduleClassLoader != null)
            return moduleClassLoader;
        try {
            File installDir = new File(System.getProperty(
                                SystemPropertyConstants.INSTALL_ROOT_PROPERTY));
            File modulesDir = new File(installDir, "modules");
            moduleClassLoader = new DirectoryClassLoader(modulesDir,
                                            CLICommand.class.getClassLoader());
            return moduleClassLoader;
        } catch (IOException ioex) {
            return null;
        }
    }
    
    private static boolean equalsStrIgnoreCase(String s, String t) {
        if (s == t) return true;
        if ((s != null) && (t != null)) {
            return s.equalsIgnoreCase(t);
        }
        return false;
    }
    
    private static boolean equalsStr(String s, String t) {
        if (s == t) return true;
        if ((s != null) && (t != null)) {
            return s.equals(t);
        }
        return false;
    }
    
}
