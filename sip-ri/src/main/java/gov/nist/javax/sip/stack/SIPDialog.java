/*
 * Mobius Software LTD
 * Copyright 2023, Mobius Software LTD and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */
package gov.nist.javax.sip.stack;

import java.io.IOException;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogDoesNotExistException;
import javax.sip.DialogState;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.SipException;
import javax.sip.Transaction;
import javax.sip.TransactionDoesNotExistException;
import javax.sip.address.Address;
import javax.sip.address.Hop;
import javax.sip.address.SipURI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.EventHeader;
import javax.sip.header.OptionTag;
import javax.sip.header.ProxyAuthorizationHeader;
import javax.sip.header.RAckHeader;
import javax.sip.header.RSeqHeader;
import javax.sip.header.RequireHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.SupportedHeader;
import javax.sip.header.TimeStampHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

import gov.nist.core.CommonLogger;
import gov.nist.core.InternalErrorHandler;
import gov.nist.core.LogLevels;
import gov.nist.core.LogWriter;
import gov.nist.core.NameValueList;
import gov.nist.core.StackLogger;
import gov.nist.javax.sip.DialogExt;
import gov.nist.javax.sip.IOExceptionEventExt;
import gov.nist.javax.sip.ListeningPointImpl;
import gov.nist.javax.sip.ReleaseReferencesStrategy;
import gov.nist.javax.sip.SipListenerExt;
import gov.nist.javax.sip.SipProviderImpl;
import gov.nist.javax.sip.Utils;
import gov.nist.javax.sip.address.AddressImpl;
import gov.nist.javax.sip.address.SipUri;
import gov.nist.javax.sip.header.Authorization;
import gov.nist.javax.sip.header.CSeq;
import gov.nist.javax.sip.header.Contact;
import gov.nist.javax.sip.header.ContactList;
import gov.nist.javax.sip.header.Event;
import gov.nist.javax.sip.header.From;
import gov.nist.javax.sip.header.MaxForwards;
import gov.nist.javax.sip.header.ProxyAuthorization;
import gov.nist.javax.sip.header.RAck;
import gov.nist.javax.sip.header.RSeq;
import gov.nist.javax.sip.header.RecordRoute;
import gov.nist.javax.sip.header.RecordRouteList;
import gov.nist.javax.sip.header.Require;
import gov.nist.javax.sip.header.Route;
import gov.nist.javax.sip.header.RouteList;
import gov.nist.javax.sip.header.SIPHeader;
import gov.nist.javax.sip.header.TimeStamp;
import gov.nist.javax.sip.header.To;
import gov.nist.javax.sip.header.Via;
import gov.nist.javax.sip.message.MessageFactoryImpl;
import gov.nist.javax.sip.message.SIPMessage;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.parser.AddressParser;
import gov.nist.javax.sip.parser.CallIDParser;
import gov.nist.javax.sip.parser.ContactParser;
import gov.nist.javax.sip.parser.RecordRouteParser;
import gov.nist.javax.sip.stack.transports.processors.MessageChannel;

/*
 * Acknowledgements:
 * 
 * Bugs in this class were reported by Antonis Karydas, Brad Templeton, Jeff Adams, Alex Rootham ,
 * Martin Le Clerk, Christophe Anzille, Andreas Bystrom, Lebing Xie, Jeroen van Bemmel. Hagai Sela
 * reported a bug in updating the route set (on RE-INVITE). Jens Tinfors submitted a bug fix and
 * the .equals method. Jan Schaumloeffel contributed a buf fix ( memory leak was happening when
 * 180 contained a To tag. Bug fixes by Vladimir Ralev (Redhat).
 * Performance enhancements and memory reduction enhancements by Jean Deruelle.
 * 
 */

/**
 * Tracks dialogs. A dialog is a peer to peer association of communicating SIP
 * entities. For INVITE transactions, a Dialog is created when a success message
 * is received (i.e. a response that has a To tag). The SIP Protocol stores
 * enough state in the message structure to extract a dialog identifier that can
 * be used to retrieve this structure from the SipStack.
 * 
 * @version 1.2 $Revision: 1.207 $ $Date: 2010-12-02 22:04:14 $
 * 
 * @author M. Ranganathan
 * 
 * 
 */

public class SIPDialog implements DialogExt {
	private static StackLogger logger = CommonLogger.getLogger(SIPDialog.class);

    private static final long serialVersionUID = -1429794423085204069L;

    protected transient AtomicBoolean dialogTerminatedEventDelivered; // prevent duplicate

    // private transient String stackTrace; // for semaphore debugging.

    protected String method;

    // delivery of the event
    protected transient boolean isAssigned;

    protected boolean reInviteFlag;

    protected transient Object applicationData; // Opaque pointer to application
    // data.

    protected transient SIPRequest originalRequest;
    // jeand : avoid keeping the original request ref above for too long (mem
    // saving)
    protected transient String originalRequestRecordRouteHeadersString;
    protected transient RecordRouteList originalRequestRecordRouteHeaders;

    // Last response (JvB: either sent or received).
    // jeand replaced the last response with only the data from it needed to
    // save on mem
    protected String lastResponseDialogId;
    protected Via lastResponseTopMostVia;
    protected Integer lastResponseStatusCode;
    protected long lastResponseCSeqNumber;
    protected long lastInviteResponseCSeqNumber;
    protected int lastInviteResponseCode;
    protected String lastResponseMethod;
    protected String lastResponseFromTag;
    protected String lastResponseToTag;

    // jeand: needed for reliable response sending but nullifyed right after the
    // ACK has been received or sent to let go of the ref ASAP
    protected SIPTransaction firstTransaction;
    // jeand needed for checking 491 but nullifyed right after the ACK has been
    // received or sent to let go of the ref ASAP
    protected SIPTransaction lastTransaction;
    protected String ongoingTransactionId;

    protected String dialogId;

    protected transient String earlyDialogId;

    protected long localSequenceNumber;

    protected long remoteSequenceNumber;

    protected String myTag;

    protected String hisTag;

    protected RouteList routeList;

    protected transient SIPTransactionStack sipStack;

    protected AtomicInteger dialogState = new AtomicInteger(NULL_STATE);

    protected transient ACKWrapper lastAckSent;
    
    // jeand : replaced the lastAckReceived message with only the data needed to
    // save on mem
    protected Long lastAckReceivedCSeqNumber;

    // could be set on recovery by examining the method looks like a duplicate
    // of ackSeen
    protected transient boolean ackProcessed;

    protected transient AtomicReference<SIPDialogTimerTask> timerTask = new AtomicReference<SIPDialogTimerTask>();
    protected AtomicBoolean timerTaskStarted = new AtomicBoolean(false);

    protected transient AtomicLong nextSeqno;

    protected long originalLocalSequenceNumber;

    // This is for debugging only.
    protected transient int ackLine;

    // Audit tag used by the SIP Stack audit
    protected transient long auditTag = 0;

    // The following fields are extracted from the request that created the
    // Dialog.

    protected javax.sip.address.Address localParty;
    protected String localPartyStringified;

    protected javax.sip.address.Address remoteParty;
    protected String remotePartyStringified;

    protected CallIdHeader callIdHeader;
    protected String callIdHeaderString;

    public final static int NULL_STATE = -1;

    public final static int EARLY_STATE = DialogState._EARLY;

    public final static int CONFIRMED_STATE = DialogState._CONFIRMED;

    public final static int TERMINATED_STATE = DialogState._TERMINATED;

    protected boolean serverTransactionFlag;

    protected transient SipProviderImpl sipProvider;

    protected boolean terminateOnBye;

    protected transient boolean byeSent; // Flag set when BYE is sent, to
    // disallow new

    // requests

    protected Address remoteTarget;
    protected String remoteTargetStringified;

    protected Event eventHeader; // for Subscribe notify

    // Stores the last OK for the INVITE
    // Used in createAck.
    protected transient long lastInviteOkReceived;

    // private transient Semaphore ackSem = new Semaphore(1);

    protected transient int reInviteWaitTime = 100;

    private transient DialogDeleteTask dialogDeleteTask;

    protected transient DialogDeleteIfNoAckSentTask dialogDeleteIfNoAckSentTask;

    protected transient boolean isAcknowledged;

    protected transient long highestSequenceNumberAcknowledged = -1;

    protected boolean isBackToBackUserAgent;

    protected boolean sequenceNumberValidation = true;

    // List of event listeners for this dialog
    //protected transient Set<SIPDialogEventListener> eventListeners;
    
    // added for Issue 248 :
    // https://jain-sip.dev.java.net/issues/show_bug.cgi?id=248
    // private transient Semaphore timerTaskLock = new Semaphore(1);

    // We store here the useful data from the first transaction without having
    // to
    // keep the whole transaction object for the duration of the dialog. It also
    // contains the non-transient information used in the replication of
    // dialogs.
    protected boolean firstTransactionSecure;
    protected boolean firstTransactionSeen;
    protected String firstTransactionMethod;
    protected String firstTransactionId;
    protected boolean firstTransactionIsServerTransaction;
    protected String firstTransactionMergeId;
    protected int firstTransactionPort = 5060;
    protected Contact contactHeader;
    protected String contactHeaderStringified;

    protected boolean pendingRouteUpdateOn202Response;

    protected ProxyAuthorization proxyAuthorizationHeader; // For
                                                                 // subequent
                                                                 // requests.

    // aggressive flag to optimize eagerly
    protected ReleaseReferencesStrategy releaseReferencesStrategy;

    private transient EarlyStateTimerTask earlyStateTimerTask;

    protected int earlyDialogTimeout = 180;

	protected Set<String> responsesReceivedInForkingCase = new HashSet<String>(0);

    protected SIPDialog originalDialog;

    // private SIPResponse pendingReliableResponse;
    // wondering if the pendingReliableResponseAsBytes could be put into the
    // lastResponseAsBytes
    protected int rseqNumber = -1;
    protected byte[] pendingReliableResponseAsBytes;
    protected String pendingReliableResponseMethod;
    protected long pendingReliableCSeqNumber;
    protected long pendingReliableRSeqNumber;

    // The pending reliable Response Timer
    protected ProvisionalResponseTask provisionalResponseTask;

    // //////////////////////////////////////////////////////
    // Inner classes
    // //////////////////////////////////////////////////////
  
    public class AckSendingStrategyImpl implements AckSendingStrategy {
        private Hop hop = null;
        private ListeningPointImpl lp = null;
        private SIPRequest ackRequest = null;
        private long startTime;
        private MessageChannel messageChannel = null;

        public AckSendingStrategyImpl(SIPRequest ackRequest, Hop hop, ListeningPointImpl lp) {
            this.ackRequest = ackRequest;
            startTime = System.currentTimeMillis();
            this.hop = hop; 
            this.lp = lp;   
        }
        
        @Override
        public void send(SIPRequest ackRequest) throws SipException, IOException, MessageTooLongException {
            InetAddress inetAddress = InetAddress.getByName(hop.getHost());
            messageChannel = lp.getMessageProcessor()
                    .createMessageChannel(inetAddress, hop.getPort());
            
            SIPDialog.this.send(messageChannel, ackRequest);
        }


        @Override
        public void execute() {
        	try {
                send(ackRequest);
                /**
                 * Notifying the application layer of the message sent out in the same thread
                 */
                if(lastTransaction.getSipProvider().getSipListener() instanceof SipListenerExt) {
                    ((SipListenerExt) lastTransaction.getSipProvider().getSipListener()).processMessageSent(
                        ackRequest, lastTransaction);
                }
            } catch (Exception ex) {                                
                raiseIOException(ackRequest, gov.nist.javax.sip.IOExceptionEventExt.Reason.ConnectionError, messageChannel.getHost(), messageChannel.getPort(), hop.getHost(), hop.getPort(), hop
                        .getTransport());
            }
            
            cancelDialogDelete();            
        }

        @Override
        public long getStartTime() {
            return startTime;
        }

        @Override
        public Hop getLastHop() {
            return hop;
        }

        @Override
        public String getId() {
            return ackRequest.getCallId().getCallId();
        }       
    }   

    // ///////////////////////////////////////////////////////////
    // Constructors.
    // ///////////////////////////////////////////////////////////
    
    protected SIPDialog() {}
    
    /**
     * Protected Dialog constructor.
     */
    protected SIPDialog(SipProviderImpl provider) {
        this.terminateOnBye = true;
        this.routeList = new RouteList();
        this.dialogState.set(NULL_STATE); // not yet initialized.
        localSequenceNumber = 0;
        remoteSequenceNumber = -1;
        this.sipProvider = provider;
        this.dialogTerminatedEventDelivered = new AtomicBoolean(false);
        this.nextSeqno = new AtomicLong(0);
        this.earlyDialogTimeout = ((SIPTransactionStack) provider.getSipStack())
                .getEarlyDialogTimeout();
    }

    // private void recordStackTrace() {
    //     StringWriter stringWriter = new StringWriter();
    //     PrintWriter writer = new PrintWriter(stringWriter);
    //     new Exception().printStackTrace(writer);
    //     String stackTraceSignature = Integer.toString(Math.abs(new Random().nextInt()));
    //     logger.logDebug("TraceRecord = " + stackTraceSignature);
    //     this.stackTrace = "TraceRecord = " + stackTraceSignature + ":" +  stringWriter.getBuffer().toString();
    // }

    public void fireEarlyStateTimer() {
        try {
            if (SIPDialog.this.getState().equals(DialogState.EARLY)) {
                
                SIPDialog.this
                        .raiseErrorEvent(SIPDialogErrorEvent.EARLY_STATE_TIMEOUT);
            } else {
                if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                    logger.logDebug("EarlyStateTimerTask : Dialog state is " + SIPDialog.this.getState());
                }
            }
        } catch (Exception ex) {
            logger.logError(
                    "Unexpected exception delivering event", ex);
        }
    }

    /**
     * Constructor given the first transaction.
     * 
     * @param transaction
     *            is the first transaction.
     */
    public SIPDialog(SIPTransaction transaction) {
        this(transaction.getSipProvider());

        SIPRequest sipRequest = (SIPRequest) transaction.getRequest();
        this.callIdHeader = sipRequest.getCallId();
        this.earlyDialogId = sipRequest.getDialogId(false);
        this.sipStack = transaction.getSIPStack();

        // this.defaultRouter = new DefaultRouter((SipStack) sipStack,
        // sipStack.outboundProxy);

        this.sipProvider = (SipProviderImpl) transaction.getSipProvider();
        if (sipProvider == null)
            throw new NullPointerException("Null Provider!");
        this.isBackToBackUserAgent = sipStack.isBackToBackUserAgent;

        this.addTransaction(transaction);
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug("Creating a dialog : " + this);
            logger.logDebug(
                    "provider port = "
                            + this.sipProvider.getListeningPoint().getPort());
            logger.logStackTrace();
        }
        releaseReferencesStrategy = sipStack.getReleaseReferencesStrategy();
    }

    /**
     * Constructor given a transaction and a response.
     * 
     * @param transaction
     *            -- the transaction ( client/server)
     * @param sipResponse
     *            -- response with the appropriate tags.
     */
    public SIPDialog(SIPClientTransaction transaction, SIPResponse sipResponse) {
        this(transaction);
        if (sipResponse == null)
            throw new NullPointerException("Null SipResponse");
        this.setLastResponse(transaction, sipResponse);
        this.isBackToBackUserAgent = sipStack.isBackToBackUserAgent;
    }

    /**
     * create a sip dialog with a response ( no tx)
     */
    public SIPDialog(SipProviderImpl sipProvider, SIPResponse sipResponse) {
        this(sipProvider);
        this.sipStack = (SIPTransactionStack) sipProvider.getSipStack();
        this.setLastResponse(null, sipResponse);
        this.localSequenceNumber = sipResponse.getCSeq().getSeqNumber();
        this.originalLocalSequenceNumber = localSequenceNumber;
        this.localParty = sipResponse.getFrom().getAddress();
        this.remoteParty = sipResponse.getTo().getAddress();
        this.method = sipResponse.getCSeq().getMethod();
        this.callIdHeader = sipResponse.getCallId();
        this.serverTransactionFlag = false;
        this.setLocalTag(sipResponse.getFrom().getTag());
        this.setRemoteTag(sipResponse.getTo().getTag());
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug("Creating a dialog : " + this);
            logger.logStackTrace();
        }
        this.isBackToBackUserAgent = sipStack.isBackToBackUserAgent;
        releaseReferencesStrategy = sipStack.getReleaseReferencesStrategy();
    }
    
    /**
     * Creates a new dialog based on a received NOTIFY. The dialog state is
     * initialized appropriately. The NOTIFY differs in the From tag
     * 
     * Made this a separate method to clearly distinguish what's happening here
     * - this is a non-trivial case
     * 
     * @param subscribeTx
     *            - the transaction started with the SUBSCRIBE that we sent
     * @param notifyST
     *            - the ServerTransaction created for an incoming NOTIFY
     * 
     * 
     */
    public SIPDialog(SIPClientTransaction subscribeTx, SIPTransaction notifyST) {
        this(notifyST);
        //
        // The above sets firstTransaction to NOTIFY (ST), correct that
        //
        serverTransactionFlag = false;
        // they share this one
        lastTransaction = subscribeTx;
        storeFirstTransactionInfo(this, subscribeTx);
        terminateOnBye = false;
        localSequenceNumber = subscribeTx.getCSeq();
        SIPRequest not = (SIPRequest) notifyST.getRequest();
        remoteSequenceNumber = not.getCSeq().getSeqNumber();
        setDialogId(not.getDialogId(true));
        setLocalTag(not.getToTag());
        setRemoteTag(not.getFromTag());
        // to properly create the Dialog object.
        // If not the stack will throw an exception when creating the response.
        setLastResponse(subscribeTx, subscribeTx.getLastResponse());

        // Dont use setLocal / setRemote here, they make other assumptions
        localParty = not.getTo().getAddress();
        remoteParty = not.getFrom().getAddress();

        // initialize d's route set based on the NOTIFY. Any proxies must have
        // Record-Routed
        addRoute(not);
        setState(CONFIRMED_STATE); // set state, *after* setting route set!
    }
    

    // ///////////////////////////////////////////////////////////
    // Private methods
    // ///////////////////////////////////////////////////////////
    /**
     * Raise an io exception for asyncrhonous retransmission of responses
     * 
     * @param host
     *            -- host to where the io was headed
     * @param port
     *            -- remote port
     * @param protocol
     *            -- protocol (udp/tcp/tls)
     */
    protected void raiseIOException(Message message, gov.nist.javax.sip.IOExceptionEventExt.Reason reason, String host, int port, String peerHost, int peerPort, String protocol) {
        // Error occured in retransmitting response.
        // Deliver the error event to the listener
        // Kill the dialog.

        IOExceptionEventExt ioError = new IOExceptionEventExt(message, this, reason, host, port, peerHost, peerPort, protocol);
        sipProvider.handleEvent(ioError, null);

        setState(SIPDialog.TERMINATED_STATE);
    }

    /**
     * Raise a dialog timeout if an ACK has not been sent or received
     * 
     * @param dialogTimeoutError
     */
    private void raiseErrorEvent(int dialogTimeoutError, SIPClientTransaction clientTransaction) {
        // Error event to send to all listeners
        SIPDialogErrorEvent newErrorEvent;
        // Iterator through the list of listeners
        
        // Create the error event
        newErrorEvent = new SIPDialogErrorEvent(this, dialogTimeoutError);
        
        
        // The client transaction can be retrieved by the application timeout handler
        // when a re-INVITE is being sent.
        newErrorEvent.setClientTransaction(clientTransaction);

        sipStack.dialogErrorEvent(newErrorEvent);
        sipProvider.dialogErrorEvent(newErrorEvent);
        
        // Errors always terminate a dialog except if a timeout has occured
        // because an ACK was not sent or received, then it is the
        // responsibility of the app to terminate
        // the dialog, either by sending a BYE or by calling delete() on the
        // dialog
        if (dialogTimeoutError != SIPDialogErrorEvent.DIALOG_ACK_NOT_SENT_TIMEOUT
                && dialogTimeoutError != SIPDialogErrorEvent.DIALOG_ACK_NOT_RECEIVED_TIMEOUT
                && dialogTimeoutError != SIPDialogErrorEvent.EARLY_STATE_TIMEOUT
                && dialogTimeoutError != SIPDialogErrorEvent.DIALOG_REINVITE_TIMEOUT) {
            delete();
        }

        // we stop the timer in any case
        stopTimer();
    }
    
    /**
     * Raise a dialog timeout if an ACK has not been sent or received
     * 
     * @param dialogTimeoutError
     */
    protected void raiseErrorEvent(int dialogTimeoutError) {
    	raiseErrorEvent(dialogTimeoutError,null);
    }

    /**
     * Set the remote party for this Dialog.
     * 
     * @param sipMessage
     *            -- SIP Message to extract the relevant information from.
     */
    protected void setRemoteParty(SIPMessage sipMessage) {

        if (!isServer()) {

            this.remoteParty = sipMessage.getTo().getAddress();
        } else {
            this.remoteParty = sipMessage.getFrom().getAddress();

        }
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "settingRemoteParty " + this.remoteParty);
        }
    }

    /**
     * Add a route list extracted from a record route list. If this is a server
     * dialog then we assume that the record are added to the route list IN
     * order. If this is a client dialog then we assume that the record route
     * headers give us the route list to add in reverse order.
     * 
     * @param recordRouteList
     *            -- the record route list from the incoming message.
     */

    private void addRoute(RecordRouteList recordRouteList) {
        try {
            if (!this.isServer()) {
                // This is a client dialog so we extract the record
                // route from the response and reverse its order to
                // careate a route list.
                this.routeList = new RouteList();
                // start at the end of the list and walk backwards

                ListIterator<RecordRoute> li = recordRouteList.listIterator(recordRouteList
                        .size());
                while (li.hasPrevious()) {
                    RecordRoute rr = (RecordRoute) li.previous();

                    Route route = new Route();
                    AddressImpl address = ((AddressImpl) ((AddressImpl) rr
                            .getAddress()).clone());

                    route.setAddress(address);
                    route.setParameters((NameValueList) rr.getParameters()
                            .clone());

                    this.routeList.add(route);
                }
            } else {
                // This is a server dialog. The top most record route
                // header is the one that is closest to us. We extract the
                // route list in the same order as the addresses in the
                // incoming request.
                this.routeList = new RouteList();
                ListIterator<RecordRoute> li = recordRouteList.listIterator();
                while (li.hasNext()) {
                    RecordRoute rr = (RecordRoute) li.next();

                    Route route = new Route();
                    AddressImpl address = ((AddressImpl) ((AddressImpl) rr
                            .getAddress()).clone());
                    route.setAddress(address);
                    route.setParameters((NameValueList) rr.getParameters()
                            .clone());
                    routeList.add(route);

                }
            }
        } finally {
            if (logger.isLoggingEnabled()) {
                Iterator<Route> it = routeList.iterator();

                while (it.hasNext()) {
                    SipURI sipUri = (SipURI) (((Route) it.next()).getAddress()
                            .getURI());
                    if (!sipUri.hasLrParam()) {
                        if (logger.isLoggingEnabled()) {
                            logger.logWarning(
                                    "NON LR route in Route set detected for dialog : "
                                            + this);
                            logger.logStackTrace();
                        }
                    } else {
                        if (logger.isLoggingEnabled(
                                LogWriter.TRACE_DEBUG))
                            logger.logDebug(
                                    "route = " + sipUri);
                    }
                }
            }
        }
    }

    /**
     * Add a route list extacted from the contact list of the incoming message.
     * 
     * @param contactList
     *            -- contact list extracted from the incoming message.
     * 
     */

    protected void setRemoteTarget(ContactHeader contact) {
        this.remoteTarget = contact.getAddress();
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "Dialog.setRemoteTarget: " + this.remoteTarget);
            logger.logStackTrace();
        }

    }

    /**
     * Extract the route information from this SIP Message and add the relevant
     * information to the route set.
     * 
     * @param sipMessage
     *            is the SIP message for which we want to add the route.
     */
    private void addRoute(SIPResponse sipResponse) {

        try {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "setContact: dialogState: " + this + "state = "
                                + this.getState());
            }
            if (sipResponse.getStatusCode() == 100) {
                // Do nothing for trying messages.
                return;
            } else if (this.dialogState.get() == TERMINATED_STATE) {
                // Do nothing if the dialog state is terminated.
                return;
            } else if (this.dialogState.get() == CONFIRMED_STATE) {
                // cannot add route list after the dialog is initialized.
                // Remote target is updated on RE-INVITE but not
                // the route list.
                if (sipResponse.getStatusCode() / 100 == 2 && !this.isServer()) {
                    ContactList contactList = sipResponse.getContactHeaders();
                    if (contactList != null
                            && SIPRequest.isTargetRefresh(sipResponse.getCSeq()
                                    .getMethod())) {
                        this.setRemoteTarget((ContactHeader) contactList
                                .getFirst());
                    }
                }
                if (!this.pendingRouteUpdateOn202Response)
                    return;
            }

            // Update route list on response if I am a client dialog.
            if (!isServer() || this.pendingRouteUpdateOn202Response) {

                // only update the route set if the dialog is not in the
                // confirmed state.
                if ((this.getState() != DialogState.CONFIRMED && this
                        .getState() != DialogState.TERMINATED)
                        || this.pendingRouteUpdateOn202Response) {
                    RecordRouteList rrlist = sipResponse
                            .getRecordRouteHeaders();
                    // Add the route set from the incoming response in reverse
                    // order for record route headers.
                    if (rrlist != null) {
                        this.addRoute(rrlist);
                    } else {
                        // Set the rotue list to the last seen route list.
                        this.routeList = new RouteList();
                    }
                }

                ContactList contactList = sipResponse.getContactHeaders();
                if (contactList != null) {
                    this
                            .setRemoteTarget((ContactHeader) contactList
                                    .getFirst());
                }
            }

        } finally {
            if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                logger.logStackTrace();
            }
        }
    }

    /**
     * Get a cloned copy of route list for the Dialog.
     * 
     * @return -- a cloned copy of the dialog route list.
     */
    private RouteList getRouteList() {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
            logger.logDebug("getRouteList " + this);
        // Find the top via in the route list.
        ListIterator<Route> li;
        RouteList retval = new RouteList();

        retval = new RouteList();
        if (this.routeList != null) {
            li = routeList.listIterator();
            while (li.hasNext()) {
                Route route = (Route) li.next();
                retval.add((Route) route.clone());
            }
        }

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug("----- ");
            logger.logDebug("getRouteList for " + this);
            if (retval != null)
                logger.logDebug(
                        "RouteList = " + retval.encode());
            if (routeList != null)
                logger.logDebug(
                        "myRouteList = " + routeList.encode());
            logger.logDebug("----- ");
        }
        return retval;
    }

    void setRouteList(RouteList routeList) {
        this.routeList = routeList;
    }

    /**
     * Send ack request on a given message channel
     * @param messageChannel
     * @param ackRequest
     * @throws SipException
     * @throws IOException
    */
    protected void send(MessageChannel messageChannel, SIPRequest ackRequest) throws SipException, IOException, MessageTooLongException {        
        messageChannel.sendMessage(ackRequest);
    }


    /**
     * Sends ACK Request to the remote party of this Dialogue.
     * 
     * 
     * @param request
     *            the new ACK Request message to send.
     * @param throwIOExceptionAsSipException
     *            - throws SipException if IOEx encountered. Otherwise, no
     *            exception is propagated.
     * @param releaseAckSem
     *            - release ack semaphore.
     * @throws SipException
     *             if implementation cannot send the ACK Request for any other
     *             reason
     * 
     */
    protected void sendAck(Request request, boolean throwIOExceptionAsSipException)
            throws SipException {

        SIPRequest ackRequest = (SIPRequest) request;
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
            logger.logDebug("sendAck: " + this);
        
        if (!ackRequest.getMethod().equals(Request.ACK))
            throw new SipException("Bad request method -- should be ACK");
        if (this.getState() == null
                || this.getState().getValue() == EARLY_STATE) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_ERROR)) {
                logger.logError(
                        "Bad Dialog State for " + this + " dialogID = "
                                + this.getDialogId());
            }
            throw new SipException("Bad dialog state " + this.getState());
        }

        if (!this.getCallId().getCallId().equals(
                ((SIPRequest) request).getCallId().getCallId())) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger
                        .logError("CallID " + this.getCallId());
                logger
                        .logError(
                                "RequestCallID = "
                                        + ackRequest.getCallId().getCallId());
                logger.logError("dialog =  " + this);
            }
            throw new SipException("Bad call ID in request");
        }
        try {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "setting from tag For outgoing ACK= "
                                + this.getLocalTag());
                logger.logDebug(
                        "setting To tag for outgoing ACK = "
                                + this.getRemoteTag());
                logger.logDebug("ack = " + ackRequest);
            }
            if (this.getLocalTag() != null)
                ackRequest.getFrom().setTag(this.getLocalTag());
            if (this.getRemoteTag() != null)
                ackRequest.getTo().setTag(this.getRemoteTag());
        } catch (ParseException ex) {
            throw new SipException(ex.getMessage());
        }

        // boolean releaseAckSem = false;
        // long cseqNo = ((SIPRequest) request).getCSeq().getSeqNumber();
        // if (!this.isAckSent(cseqNo)) {
        //     releaseAckSem = true;
        // }
        // we clone the request that we store to make sure that if getNextHop modifies the request
        // we store it as it was passed to the method originally
        this.setLastAckSent(ackRequest);
        
        Hop hop = sipStack.getNextHop(ackRequest);
        if (hop == null)
            throw new SipException("No route!");
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
            logger.logDebug("hop = " + hop);
        ListeningPointImpl lp = (ListeningPointImpl) sipProvider
                .getListeningPoint(hop.getTransport());
        if (lp == null)
            throw new SipException(
                    "No listening point for this provider registered at "
                            + hop);
              
        // Sent atleast one ACK.
        this.isAcknowledged = true;
        this.highestSequenceNumberAcknowledged = Math.max(
                this.highestSequenceNumberAcknowledged,
                ((SIPRequest) ackRequest).getCSeq().getSeqNumber());
        AckSendingStrategy ackSendingStrategy = new AckSendingStrategyImpl(ackRequest, hop, lp);
        sipStack.getMessageProcessorExecutor().addTaskFirst(ackSendingStrategy);
        // if (releaseAckSem && this.isBackToBackUserAgent) {
        //     this.releaseAckSem();
        // } else {
        //     if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
        //         logger.logDebug(
        //                 "Not releasing ack sem for " + this + " isAckSent "
        //                         + releaseAckSem);
        //     }
        // }
    }

    // /////////////////////////////////////////////////////////////
    // Package local methods
    // /////////////////////////////////////////////////////////////

    /**
     * Set the stack address. Prevent us from routing messages to ourselves.
     * 
     * @param sipStack
     *            the address of the SIP stack.
     * 
     */
    void setStack(SIPTransactionStack sipStack) {
        this.sipStack = sipStack;

    }

    /**
     * Get the stack .
     * 
     * @return sipStack the SIP stack of the dialog.
     * 
     */
    SIPTransactionStack getStack() {
        return sipStack;
    }

    /**
     * Return True if this dialog is terminated on BYE.
     * 
     */
    boolean isTerminatedOnBye() {

        return this.terminateOnBye;
    }

    /**
     * Mark that the dialog has seen an ACK.
     */
    void ackReceived(long cseqNumber) {
        // Suppress retransmission of the final response
        if (this.isAckSeen()) {
            if (logger.isLoggingEnabled(
                    LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "Ack already seen for response -- dropping");
            return;
        }
        SIPServerTransaction tr = this.getInviteTransaction();
        if (tr != null) {
            if (tr.getCSeq() == cseqNumber) {
                // acquireTimerTaskSem();
                // try {
                    stopTimer();
                // } finally {
                //     releaseTimerTaskSem();
                // }
                if (this.dialogDeleteTask != null) {
                    this.getStack().getTimer().cancel(dialogDeleteTask);
                    this.dialogDeleteTask = null;
                }
                lastAckReceivedCSeqNumber = Long.valueOf(cseqNumber);
                if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                    logger.logDebug(
                            "ackReceived for "
                                    + ((SIPTransaction) tr).getMethod());
                    this.ackLine = logger.getLineCount();
                    this.printDebugInfo();
                }
                // if (this.isBackToBackUserAgent) {
                //     this.releaseAckSem();
                // }
                this.setState(CONFIRMED_STATE);
            }
        } else {
            if (logger.isLoggingEnabled(
                    LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "tr is null -- not updating the ack state");
        }
    }

    /**
     * Return true if a terminated event was delivered to the application as a
     * result of the dialog termination.
     * 
     */
    boolean testAndSetIsDialogTerminatedEventDelivered() {
        return this.dialogTerminatedEventDelivered.getAndSet(true);
        // boolean retval = this.dialogTerminatedEventDelivered;
        // this.dialogTerminatedEventDelivered = true;
        // return retval;
    }

    // /////////////////////////////////////////////////////////
    // Public methods
    // /////////////////////////////////////////////////////////    

    /*
     * @see javax.sip.Dialog#setApplicationData()
     */
    public void setApplicationData(Object applicationData) {
        this.applicationData = applicationData;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getApplicationData()
     */
    public Object getApplicationData() {
        return this.applicationData;
    }

    /**
     * Updates the next consumable seqno.
     * 
     */
    public void requestConsumed() {
        nextSeqno.set(this.getRemoteSeqNumber() + 1);

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "Request Consumed -- next consumable Request Seqno = "
                            + this.nextSeqno.get());
        }

    }

    /**
     * Return true if this request can be consumed by the dialog.
     * 
     * @param dialogRequest
     *            is the request to check with the dialog.
     * @return true if the dialogRequest sequence number matches the next
     *         consumable seqno.
     */
    public boolean isRequestConsumable(SIPRequest dialogRequest) {
        // have not yet set remote seqno - this is a fresh
        if (dialogRequest.getMethod().equals(Request.ACK))
            throw new RuntimeException("Illegal method");

        // For loose validation this function is delegated to the application
        if (!this.isSequenceNumberValidation()) {
            return true;
        }

        // JvB: Acceptable iff remoteCSeq < cseq. remoteCSeq==-1
        // when not defined yet, so that works too
        return remoteSequenceNumber < dialogRequest.getCSeq().getSeqNumber();
    }

    /**
     * This method is called when a forked dialog is created from the client
     * side. It starts a timer task. If the timer task expires before an ACK is
     * sent then the dialog is cancelled (i.e. garbage collected ).
     * 
     */
    public void doDeferredDelete() {
        if (sipStack.getTimer() == null)
            this.setState(TERMINATED_STATE);
        else {
        	String dialogId = this.dialogId;
        	if(dialogId == null)
        		dialogId = this.earlyDialogId;
        	
            this.dialogDeleteTask = new DialogDeleteTask(getCallId().getCallId(), this);
            // Delete the transaction after the max ack timeout.
            if (sipStack.getTimer() != null && sipStack.getTimer().isStarted()) {
            	int delay = SIPTransactionStack.BASE_TIMER_INTERVAL;
            	if(lastTransaction != null) {
            		delay = lastTransaction.getBaseTimerInterval();
            	}
            	sipStack.getTimer().schedule(
                    this.dialogDeleteTask,
                    SIPTransaction.TIMER_H
                            * delay);
            } else {
            	this.delete();
            }
        }

    }

    /**
     * Set the state for this dialog.
     * 
     * @param state
     *            is the state to set for the dialog.
     */

    public void setState(int state) {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "SIPDialog::setState:Setting dialog state for " + this + "newState = " + state);
            logger.logStackTrace();
            if (state != NULL_STATE && state != this.dialogState.get())
                if (logger.isLoggingEnabled()) {
                    logger.logDebug("SIPDialog::setState:" +	
                            this + "  old dialog state is " + this.getState());
                    logger.logDebug("SIPDialog::setState:" +
                            this + "  New dialog state is "
                                    + DialogState.getObject(state));
                }
        }

        if(state == TERMINATED_STATE)
        {
        	int oldState = this.dialogState.getAndSet(state);        	
        	// Dialog is in terminated state set it up for GC.
            if (oldState!=TERMINATED_STATE) {
            	if (sipStack.getTimer() != null && sipStack.getTimer().isStarted() ) { // may be null after shutdown
            		String dialogId = getDialogId();
            		if(dialogId == null)
            			dialogId = getEarlyDialogId();
            		
            		if(dialogId!=null) {        		
            			if(sipStack.getConnectionLingerTimer() > 0) {
    	            		sipStack.getTimer().schedule(new DialogLingerTimer(sipStack, sipProvider, this, getCallId().getCallId()),
    	            				sipStack.getConnectionLingerTimer() * 1000);
    	            	} else {
    	            		new DialogLingerTimer(sipStack, sipProvider, this, getCallId().getCallId()).runTask();
    	            	}
            		}
                }
                this.stopTimer();
            }
        }
        else
        	this.dialogState.set(state);        
    }

    /**
     * Debugging print for the dialog.
     */
    public void printDebugInfo() {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug("isServer = " + isServer());
            logger.logDebug("localTag = " + getLocalTag());
            logger.logDebug("remoteTag = " + getRemoteTag());
            logger.logDebug(
                    "localSequenceNumer = " + getLocalSeqNumber());
            logger.logDebug(
                    "remoteSequenceNumer = " + getRemoteSeqNumber());
            logger.logDebug(
                    "ackLine:" + this.getRemoteTag() + " " + ackLine);
        }
    }

    /**
     * Return true if the dialog has already seen the ack.
     * 
     * @return flag that records if the ack has been seen.
     */
    public boolean isAckSeen() {

        if (lastAckReceivedCSeqNumber == null
                && lastResponseStatusCode == Response.OK) {
            if (logger.isLoggingEnabled(
                    LogWriter.TRACE_DEBUG)) {
                logger.logDebug("SIPDialog::isAckSeen:"+
                        this + "lastAckReceived is null -- returning false");
            }
            return false;
        } else if (lastResponseMethod == null) {
            if (logger.isLoggingEnabled(
                    LogWriter.TRACE_DEBUG)) {
                logger.logDebug("SIPDialog::isAckSeen:"+
                        this + "lastResponse is null -- returning false");
            }
            return false;
        } else if (lastAckReceivedCSeqNumber == null
                && lastResponseStatusCode / 100 > 2) {
            if (logger.isLoggingEnabled(
                    LogWriter.TRACE_DEBUG)) {
                logger.logDebug("SIPDialog::isAckSeen:"+
                        this + "lastResponse statusCode "
                                + lastResponseStatusCode);
            }
            return true;
        } else {
        	if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
        		logger.logDebug("SIPDialog::isAckSeen:lastAckReceivedCSeqNumber = " + lastAckReceivedCSeqNumber + " remoteCSeqNumber = " + this.getRemoteSeqNumber());
        	}
            return this.lastAckReceivedCSeqNumber != null
                    && this.lastAckReceivedCSeqNumber >= this
                            .getRemoteSeqNumber();
        }
    }
    
    
    public long getLastAckSentCSeq() {
        return lastAckSent != null ? lastAckSent.getCSeq() : -1;
    }    
    public String getLastAckSentFromTag() {
        return lastAckSent != null ? lastAckSent.getFromTag() : null;
    }
    public String getLastAckSentDialogId() {
        return lastAckSent != null ? lastAckSent.getDialogId() : null;
    }
    
    public boolean isLastAckPresent(){
    	return lastAckSent != null;
    }
    
    /**
     * 
     * @return same as callling isAckSent(-1)
     */
    public boolean isAckSent() {
        return isAckSent(-1);
    }

    /**
     * Return true if ACK was sent ( for client tx ). For server tx, this is a
     * NO-OP ( we dont send ACK).
     */
    public boolean isAckSent(long cseqNo) {
        if (this.getLastTransaction() == null)
            return true;
        if (this.getLastTransaction() instanceof ClientTransaction) {
            if (lastAckSent == null) {
                return false;
            } else {
                return cseqNo <= this.getLastAckSentCSeq();
            }
        } else {
            return true;
        }
    }

    @Deprecated
    public Transaction getFirstTransaction() {
        throw new UnsupportedOperationException(
                "This method has been deprecated and is no longer supported");
    }

    /**
     * This is for internal use only.
     * 
     */
    public Transaction getFirstTransactionInt() {
        // jeand : we try to avoid keeping the ref around for too long to help
        // the GC
        if (firstTransaction != null) {
            return firstTransaction;
        }
        return this.sipStack.findTransaction(firstTransactionId,
                firstTransactionIsServerTransaction);
    }

    /**
     * Gets the route set for the dialog. When acting as an User Agent Server
     * the route set MUST be set to the list of URIs in the Record-Route header
     * field from the request, taken in order and preserving all URI parameters.
     * When acting as an User Agent Client the route set MUST be set to the list
     * of URIs in the Record-Route header field from the response, taken in
     * reverse order and preserving all URI parameters. If no Record-Route
     * header field is present in the request or response, the route set MUST be
     * set to the empty set. This route set, even if empty, overrides any
     * pre-existing route set for future requests in this dialog.
     * <p>
     * Requests within a dialog MAY contain Record-Route and Contact header
     * fields. However, these requests do not cause the dialog's route set to be
     * modified.
     * <p>
     * The User Agent Client uses the remote target and route set to build the
     * Request-URI and Route header field of the request.
     * 
     * @return an Iterator containing a list of route headers to be used for
     *         forwarding. Empty iterator is returned if route has not been
     *         established.
     */
    public Iterator<Route> getRouteSet() {
        if (this.routeList == null) {
            return new LinkedList<Route>().listIterator();
        } else {
            return this.getRouteList().listIterator();
        }
    }

    /**
     * Add a Route list extracted from a SIPRequest to this Dialog.
     * 
     * @param sipRequest
     */
    public void addRoute(SIPRequest sipRequest) {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "setContact: dialogState: " + this + "state = "
                            + this.getState());
        }

        if (this.dialogState.get() == CONFIRMED_STATE
                && SIPRequest.isTargetRefresh(sipRequest.getMethod())) {
            this.doTargetRefresh(sipRequest);
        }
        if (this.dialogState.get() == CONFIRMED_STATE
                || this.dialogState.get() == TERMINATED_STATE) {
            return;
        }
        
        // put the contact header from the incoming request into
        // the route set. JvB: some duplication here, ref. doTargetRefresh
        ContactList contactList = sipRequest.getContactHeaders();
        if (contactList != null) {
            this.setRemoteTarget((ContactHeader) contactList.getFirst());
        }

        // Fix for issue #225: mustn't learn Route set from mid-dialog requests
        if (sipRequest.getToTag() != null)
            return;

        // Incoming Request has the route list
        RecordRouteList rrlist = sipRequest.getRecordRouteHeaders();
        // Add the route set from the incoming response in reverse
        // order
        if (rrlist != null) {
            this.addRoute(rrlist);
        } else {
            // Set the rotue list to the last seen route list.
            this.routeList = new RouteList();
        }

    }

    /**
     * Set the dialog identifier.
     */
    public void setDialogId(String dialogId) {
        if (firstTransaction != null) {
            firstTransaction.setDialog(this, dialogId);
        }
        this.dialogId = dialogId;
    }

    /**
     * Return true if is server.
     * 
     * @return true if is server transaction created this dialog.
     */
    public boolean isServer() {
        if (this.firstTransactionSeen == false)
            return this.serverTransactionFlag;
        else
            return this.firstTransactionIsServerTransaction;

    }

    /**
     * Return true if this is a re-establishment of the dialog.
     * 
     * @return true if the reInvite flag is set.
     */
    protected boolean isReInvite() {
        return this.reInviteFlag;
    }

    /**
     * Get the id for this dialog.
     * 
     * @return the string identifier for this dialog.
     * 
     */
    public String getDialogId() {

        if (this.dialogId == null && this.lastResponseDialogId != null)
            this.dialogId = this.lastResponseDialogId;

        return this.dialogId;
    }

    protected void storeFirstTransactionInfo(SIPDialog dialog,
            SIPTransaction transaction) {
       
        dialog.firstTransactionSeen = true;
        dialog.firstTransaction = transaction;            
        dialog.firstTransactionIsServerTransaction = transaction
                .isServerTransaction();
        if (dialog.firstTransactionIsServerTransaction) {
            dialog.firstTransactionSecure = transaction.getRequest()
                    .getRequestURI().getScheme().equalsIgnoreCase("sips");
        } else {
            dialog.firstTransactionSecure = ((SIPClientTransaction) transaction)
                    .getOriginalRequestScheme().equalsIgnoreCase("sips");
        }
        dialog.firstTransactionPort = transaction.getPort();
        dialog.firstTransactionId = transaction.getBranchId();
        dialog.firstTransactionMethod = transaction.getMethod();
        if (transaction instanceof SIPServerTransaction
                && dialog.firstTransactionMethod.equals(Request.INVITE)) {
        	sipStack.removeMergeDialog(firstTransactionMergeId);
    		dialog.firstTransactionMergeId = transaction.getMergeId();
    		sipStack.putMergeDialog(this);            
        }
       
        if (transaction.isServerTransaction()) {
            SIPServerTransaction st = (SIPServerTransaction) transaction;
            SIPResponse response = st.getLastResponse();
            dialog.contactHeader = response != null ? response
                    .getContactHeader() : null;
        } else {
            SIPClientTransaction ct = (SIPClientTransaction) transaction;
            if (ct != null) {
                dialog.contactHeader = ct.getOriginalRequestContact();
            }
        }
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug("firstTransaction = " + dialog.firstTransaction);
            logger.logDebug("firstTransactionIsServerTransaction = " + firstTransactionIsServerTransaction);
            logger.logDebug("firstTransactionSecure = " + firstTransactionSecure);
            logger.logDebug("firstTransactionPort = " + firstTransactionPort);
            logger.logDebug("firstTransactionId = " + firstTransactionId);
            logger.logDebug("firstTransactionMethod = " + firstTransactionMethod);
            logger.logDebug("firstTransactionMergeId = " + firstTransactionMergeId);
        }
    }

    /**
     * Add a transaction record to the dialog.
     * 
     * @param transaction
     *            is the transaction to add to the dialog.
     */
    public boolean addTransaction(SIPTransaction transaction) {

        SIPRequest sipRequest = (SIPRequest) transaction.getOriginalRequest();
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "SipDialog.addTransaction() firstTransactionSeen = "
                            + firstTransactionSeen + 
                            ", firstTransaction = " + firstTransaction + 
                            ", firstTransactionId = " + firstTransactionId + 
                            ", transactionId = " + transaction.getBranchId() +
                            ", firstTransactionMethod = " + firstTransactionMethod + 
                            ", transactionMethod = " + transaction.getMethod() +
                            ", firstTransactionIsServerTransaction = " + firstTransactionIsServerTransaction +
                            ", transactionIsServerTransaction = " + transaction.isServerTransaction());
        }
        // Proessing a re-invite.
        if (firstTransactionSeen
                && !firstTransactionId.equals(transaction.getBranchId())
                && transaction.getMethod().equals(firstTransactionMethod)) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                    "SipDialog.addTransaction() setting reinviteFlag to true");
            }
            setReInviteFlag(true);
        }

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "SipDialog.addTransaction() " + this + " transaction = "
                            + transaction);
        }

        if (firstTransactionSeen == false) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                    "SipDialog.addTransaction() first tx not seen");
            }

            // Record the local and remote sequenc
            // numbers and the from and to tags for future
            // use on this dialog.
            storeFirstTransactionInfo(this, transaction);
            if (sipRequest.getMethod().equals(Request.SUBSCRIBE))
                this.eventHeader = (Event) sipRequest
                        .getHeader(EventHeader.NAME);

            this.setLocalParty(sipRequest);
            this.setRemoteParty(sipRequest);
            this.setCallId(sipRequest);
            if (this.originalRequest == null
                    && transaction.isInviteTransaction()) {
                this.originalRequest = sipRequest;
            } else if (originalRequest != null) {
                originalRequestRecordRouteHeaders = sipRequest
                        .getRecordRouteHeaders();
            }
            if (this.method == null) {
                this.method = sipRequest.getMethod();
            }

            if (transaction instanceof SIPServerTransaction) {
                this.hisTag = sipRequest.getFrom().getTag();
                // My tag is assigned when sending response
            } else {
                setLocalSequenceNumber(sipRequest.getCSeq().getSeqNumber());
                this.originalLocalSequenceNumber = getLocalSeqNumber();
                this.setLocalTag(sipRequest.getFrom().getTag());
                if (myTag == null)
                    if (logger.isLoggingEnabled())
                        logger
                                .logError(
                                        "The request's From header is missing the required Tag parameter.");
            }
        } else if (transaction.getMethod().equals(firstTransactionMethod)
                && firstTransactionIsServerTransaction != transaction
                        .isServerTransaction()) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                    "SipDialog.addTransaction() first tx seen, reinvite");
            }
            // This case occurs when you are processing a re-invite.
            // Switch from client side to server side for re-invite
            // (put the other side on hold).

            storeFirstTransactionInfo(this, transaction);

            this.setLocalParty(sipRequest);
            this.setRemoteParty(sipRequest);
            this.setCallId(sipRequest);
            if (transaction.isInviteTransaction()) {
                this.originalRequest = sipRequest;
            } else {
                originalRequestRecordRouteHeaders = sipRequest
                        .getRecordRouteHeaders();
            }
            this.method = sipRequest.getMethod();

        } else if (firstTransaction == null
                && transaction.isInviteTransaction()) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                    "SipDialog.addTransaction() first tx null");
            }
            // jeand needed for reinvite reliable processing
            firstTransaction = transaction;
        }
        if (transaction instanceof SIPServerTransaction) {
            setRemoteSequenceNumber(sipRequest.getCSeq().getSeqNumber());
        }

        // If this is a server transaction record the remote
        // sequence number to avoid re-processing of requests
        // with the same sequence number directed towards this
        // dialog.

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "isBackToBackUserAgent = " + this.isBackToBackUserAgent);
        }
        if (transaction.isInviteTransaction()) {
        	if ( logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
        		 logger.logDebug("SIPDialog::setLastTransaction:dialog= " + SIPDialog.this + " lastTransaction = " + transaction);
        	}
            this.lastTransaction = transaction;
        }
        
        try {
        	if (transaction.getRequest().getMethod().equals(Request.REFER) && transaction instanceof SIPServerTransaction) {
        		/*
                 * RFC-3515 Section - 2.4.6, if there are multiple REFER transactions in a dialog then the 
                 * NOTIFY MUST include an id parameter in the Event header containing the sequence number 
                 * (the number from the CSeq header field value) of the REFER this NOTIFY is associated with. 
                 * This id parameter MAY be included in NOTIFYs to the first REFER a UA receives in a given dialog 
                 */
        		long lastReferCSeq = ((SIPRequest) transaction.getRequest()).getCSeq().getSeqNumber();
        		this.eventHeader = new Event();
        		this.eventHeader.setEventType("refer");
        		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
        			logger.logDebug("SIPDialog::setLastTransaction:lastReferCSeq = " + lastReferCSeq);
        		}
        		this.eventHeader.setEventId(Long.toString(lastReferCSeq));
        	}
        } catch (Exception ex) {
        	logger.logFatalError("Unexpected exception in REFER processing");
        }

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "Transaction Added " + this + myTag + "/" + hisTag);
            logger.logDebug(
                    "TID = " + transaction.getTransactionId() + "/"
                            + transaction.isServerTransaction());
            logger.logStackTrace();
        }
        return true;
    }

    /**
     * Set the remote tag.
     * 
     * @param hisTag
     *            is the remote tag to set.
     */
    protected void setRemoteTag(String hisTag) {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "setRemoteTag(): " + this + " remoteTag = " + this.hisTag
                            + " new tag = " + hisTag);
        }
        if (this.hisTag != null && hisTag != null
                && !hisTag.equals(this.hisTag)) {
            if (this.getState() != DialogState.EARLY) {
                if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                    logger
                            .logDebug(
                                    "Dialog is already established -- ignoring remote tag re-assignment");
                return;
            } else if (sipStack.isRemoteTagReassignmentAllowed()) {
                if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                    logger
                            .logDebug(
                                    "UNSAFE OPERATION !  tag re-assignment "
                                            + this.hisTag
                                            + " trying to set to " + hisTag
                                            + " can cause unexpected effects ");
                boolean removed = false;
                if (this.sipStack.getDialog(dialogId) == this) {
                    this.sipStack.removeDialog(dialogId);
                    removed = true;

                }
                // Force recomputation of Dialog ID;
                this.dialogId = null;
                this.hisTag = hisTag;
                if (removed) {
                    if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                        logger
                                .logDebug("ReInserting Dialog");
                    this.sipStack.putDialog(this);
                }
            }
        } else {
            if (hisTag != null) {
                this.hisTag = hisTag;
            } else {
                if (logger.isLoggingEnabled())
                    logger.logWarning(
                            "setRemoteTag : called with null argument ");
            }
        }
    }

    /**
     * Get the last transaction from the dialog.
     */
    public SIPTransaction getLastTransaction() {
        return this.lastTransaction;
    }

    /**
     * Get the INVITE transaction (null if no invite transaction).
     */
    public SIPServerTransaction getInviteTransaction() {
        // SIPDialogTimerTask t = this.timerTask;
        if (ongoingTransactionId != null) {
            return (SIPServerTransaction) sipStack.findTransaction(ongoingTransactionId, true);
        } else {
            return null;
        }
    }

    /**
     * Set the local sequece number for the dialog (defaults to 1 when the
     * dialog is created).
     * 
     * @param lCseq
     *            is the local cseq number.
     * 
     */
    protected void setLocalSequenceNumber(long lCseq) {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
            logger.logDebug(
                    "setLocalSequenceNumber: original  "
                            + this.localSequenceNumber + " new  = " + lCseq);
        if (lCseq <= this.localSequenceNumber)
            throw new RuntimeException("Sequence number should not decrease !");
        this.localSequenceNumber = lCseq;
    }

    /**
     * Set the remote sequence number for the dialog.
     * 
     * @param rCseq
     *            is the remote cseq number.
     * 
     */
    public void setRemoteSequenceNumber(long rCseq) {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
            logger.logDebug(
                    "setRemoteSeqno " + this + "/" + rCseq);
        this.remoteSequenceNumber = rCseq;
    }

    /**
     * Increment the local CSeq # for the dialog. This is useful for if you want
     * to create a hole in the sequence number i.e. route a request outside the
     * dialog and then resume within the dialog.
     */
    public void incrementLocalSequenceNumber() {
        ++this.localSequenceNumber;
    }

    /**
     * Get the remote sequence number (for cseq assignment of outgoing requests
     * within this dialog).
     * 
     * @deprecated
     * @return local sequence number.
     */

    public int getRemoteSequenceNumber() {
        return (int) this.remoteSequenceNumber;
    }

    /**
     * Get the local sequence number (for cseq assignment of outgoing requests
     * within this dialog).
     * 
     * @deprecated
     * @return local sequence number.
     */

    public int getLocalSequenceNumber() {
        return (int) this.localSequenceNumber;
    }

    /**
     * Get the sequence number for the request that origianlly created the
     * Dialog.
     * 
     * @return -- the original starting sequence number for this dialog.
     */
    public long getOriginalLocalSequenceNumber() {
        return this.originalLocalSequenceNumber;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getLocalSequenceNumberLong()
     */
    public long getLocalSeqNumber() {
        return this.localSequenceNumber;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getRemoteSequenceNumberLong()
     */
    public long getRemoteSeqNumber() {
        return this.remoteSequenceNumber;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getLocalTag()
     */
    public String getLocalTag() {
        return this.myTag;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getRemoteTag()
     */
    public String getRemoteTag() {

        return hisTag;
    }

    /**
     * Set local tag for the transaction.
     * 
     * @param mytag
     *            is the tag to use in From headers client transactions that
     *            belong to this dialog and for generating To tags for Server
     *            transaction requests that belong to this dialog.
     */
    protected void setLocalTag(String mytag) {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "set Local tag " + mytag + " dialog = " + this);
            logger.logStackTrace();
        }

        this.myTag = mytag;

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#delete()
     */

    public void delete() {
        // the reaper will get him later.
        this.setState(TERMINATED_STATE);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getCallId()
     */
    public CallIdHeader getCallId() {
        // jeand : we save the header in a string form and reparse it, help GC
        // for dialogs updated not too often
        if (callIdHeader == null && callIdHeaderString != null) {
            try {
                this.callIdHeader = (CallIdHeader) new CallIDParser(
                        callIdHeaderString).parse();
            } catch (ParseException e) {
                logger.logError(
                        "error reparsing the call id header", e);
            }            
        }
        return this.callIdHeader;
    }

    /**
     * set the call id header for this dialog.
     */
    private void setCallId(SIPRequest sipRequest) {
        this.callIdHeader = sipRequest.getCallId();
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getLocalParty()
     */

    public javax.sip.address.Address getLocalParty() {
        // jeand : we save the address in a string form and reparse it, help GC
        // for dialogs updated not too often
        if (localParty == null && localPartyStringified != null) {
            try {
                this.localParty = (Address) new AddressParser(
                        localPartyStringified).address(true);
            } catch (ParseException e) {
                logger.logError(
                        "error reparsing the localParty", e);
            }
        }
        return this.localParty;
    }

    protected void setLocalParty(SIPMessage sipMessage) {
        if (!isServer()) {
            this.localParty = sipMessage.getFrom().getAddress();
        } else {
            this.localParty = sipMessage.getTo().getAddress();
        }
    }

    /**
     * Returns the Address identifying the remote party. This is the value of
     * the To header of locally initiated requests in this dialogue when acting
     * as an User Agent Client.
     * <p>
     * This is the value of the From header of recieved responses in this
     * dialogue when acting as an User Agent Server.
     * 
     * @return the address object of the remote party.
     */
    public javax.sip.address.Address getRemoteParty() {
        // jeand : we save the address in a string form and reparse it, help GC
        // for dialogs updated not too often
        if (remoteParty == null && remotePartyStringified != null) {
            try {
                this.remoteParty = (Address) new AddressParser(
                        remotePartyStringified).address(true);
            } catch (ParseException e) {
                logger.logError(
                        "error reparsing the remoteParty", e);
            }
        }
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "gettingRemoteParty " + this.remoteParty);
        }
        return this.remoteParty;

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getRemoteTarget()
     */
    public javax.sip.address.Address getRemoteTarget() {
        // jeand : we save the address in a string form and reparse it, help GC
        // for dialogs updated not too often
        if (remoteTarget == null && remoteTargetStringified != null) {
            try {
                this.remoteTarget = (Address) new AddressParser(
                        remoteTargetStringified).address(true);
            } catch (ParseException e) {
                logger.logError(
                        "error reparsing the remoteTarget", e);
            }
        }
        return this.remoteTarget;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#getState()
     */
    public DialogState getState() {
    	int state = this.dialogState.get();
    	if (state == NULL_STATE)
            return null; // not yet initialized
        return DialogState.getObject(state);
    }

    /**
     * Returns true if this Dialog is secure i.e. if the request arrived over
     * TLS, and the Request-URI contained a SIPS URI, the "secure" flag is set
     * to TRUE.
     * 
     * @return <code>true</code> if this dialogue was established using a sips
     *         URI over TLS, and <code>false</code> otherwise.
     */
    public boolean isSecure() {
        return this.firstTransactionSecure;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#sendAck(javax.sip.message.Request)
     */
    public void sendAck(Request request) throws SipException {
        this.sendAck(request, true);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#createRequest(java.lang.String)
     */
    public Request createRequest(String method) throws SipException {

        if (method.equals(Request.ACK) || method.equals(Request.PRACK)) {
            throw new SipException(
                    "Invalid method specified for createRequest:" + method);
        }
        if (lastResponseTopMostVia != null)
            return this.createRequest(method, this.lastResponseTopMostVia
                    .getTransport());
        else
            throw new SipException("Dialog not yet established -- no response!");
    }

    /**
     * The method that actually does the work of creating a request.
     * 
     * @param method
     * @param response
     * @return
     * @throws SipException
     */
    private SIPRequest createRequest(String method, String topMostViaTransport)
            throws SipException {
        /*
         * Check if the dialog is in the right state (RFC 3261 section 15). The
         * caller's UA MAY send a BYE for either CONFIRMED or EARLY dialogs, and
         * the callee's UA MAY send a BYE on CONFIRMED dialogs, but MUST NOT
         * send a BYE on EARLY dialogs.
         * 
         * Throw out cancel request.
         */

        if (method == null || topMostViaTransport == null)
            throw new NullPointerException("null argument");

        if (method.equals(Request.CANCEL))
            throw new SipException("Dialog.createRequest(): Invalid request");

        if (this.getState() == null
                || (this.getState().getValue() == TERMINATED_STATE && !method
                        .equalsIgnoreCase(Request.BYE))
                || (this.isServer()
                        && this.getState().getValue() == EARLY_STATE && method
                        .equalsIgnoreCase(Request.BYE)))
            throw new SipException("Dialog  " + getDialogId()
                    + " not yet established or terminated " + this.getState());

        SipUri sipUri = null;
        if (this.getRemoteTarget() != null)
            sipUri = (SipUri) this.getRemoteTarget().getURI().clone();
        else {
            sipUri = (SipUri) this.getRemoteParty().getURI().clone();
            sipUri.clearUriParms();
        }

        CSeq cseq = new CSeq();
        try {
            cseq.setMethod(method);
            cseq.setSeqNumber(this.getLocalSeqNumber());
        } catch (Exception ex) {
            if (logger.isLoggingEnabled())
                logger.logError("Unexpected error");
            InternalErrorHandler.handleException(ex);
        }
        /*
         * Add a via header for the outbound request based on the transport of
         * the message processor.
         */

        ListeningPointImpl lp = (ListeningPointImpl) this.sipProvider
                .getListeningPoint(topMostViaTransport);
        if (lp == null) {
            if (logger.isLoggingEnabled())
                logger.logError(
                        "Cannot find listening point for transport "
                                + topMostViaTransport);
            throw new SipException("Cannot find listening point for transport "
                    + topMostViaTransport);
        }
        Via via = lp.getViaHeader();

        From from = new From();
        from.setAddress(this.getLocalParty());
        To to = new To();
        to.setAddress(this.getRemoteParty());
        SIPRequest sipRequest = createRequest(sipUri, via, cseq, from, to);

        /*
         * The default contact header is obtained from the provider. The
         * application can override this.
         * 
         * JvB: Should only do this for target refresh requests, ie not for BYE,
         * PRACK, etc
         */

        if (SIPRequest.isTargetRefresh(method)) {
            ContactHeader contactHeader = ((ListeningPointImpl) this.sipProvider
                    .getListeningPoint(lp.getTransport()))
                    .createContactHeader();

            ((SipURI) contactHeader.getAddress().getURI()).setSecure(this
                    .isSecure());
            sipRequest.setHeader(contactHeader);
        }

        try {
            /*
             * Guess of local sequence number - this is being re-set when the
             * request is actually dispatched
             */
            cseq = (CSeq) sipRequest.getCSeq();
            cseq.setSeqNumber(getLocalSeqNumber() + 1);
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "SIPDialog::createRequest:setting Request " + method + " Seq Number to " + cseq.getSeqNumber());
                logger.logStackTrace();
            }
        } catch (InvalidArgumentException ex) {
            InternalErrorHandler.handleException(ex);
        }

        if (method.equals(Request.SUBSCRIBE)) {
            if (eventHeader != null) 
                sipRequest.addHeader(eventHeader);
        }
        /*
         * RFC-3515 Section - 2.4.6, if there are multiple REFER transactions in a dialog then the 
         * NOTIFY MUST include an id parameter in the Event header containing the sequence number 
         * (the number from the CSeq header field value) of the REFER this NOTIFY is associated with. 
         * This id parameter MAY be included in NOTIFYs to the first REFER a UA receives in a given dialog 
         */
        if (method.equals(Request.NOTIFY)) {
        	if (eventHeader != null ) {
        		sipRequest.addHeader(eventHeader);
        	}
        }

        /*
         * RFC3261, section 12.2.1.1:
         * 
         * The URI in the To field of the request MUST be set to the remote URI
         * from the dialog state. The tag in the To header field of the request
         * MUST be set to the remote tag of the dialog ID. The From URI of the
         * request MUST be set to the local URI from the dialog state. The tag
         * in the From header field of the request MUST be set to the local tag
         * of the dialog ID. If the value of the remote or local tags is null,
         * the tag parameter MUST be omitted from the To or From header fields,
         * respectively.
         */

        try {
            if (this.getLocalTag() != null) {
                from.setTag(this.getLocalTag());
            } else {
                from.removeTag();
            }
            if (this.getRemoteTag() != null) {
                to.setTag(this.getRemoteTag());
            } else {
                to.removeTag();
            }
        } catch (ParseException ex) {
            InternalErrorHandler.handleException(ex);
        }

        // get the route list from the dialog.
        
        this.updateRequest(sipRequest);
        
      

        return sipRequest;

    }

    /**
     * Generate a request from a response.
     * 
     * @param requestURI
     *            -- the request URI to assign to the request.
     * @param via
     *            -- the Via header to assign to the request
     * @param cseq
     *            -- the CSeq header to assign to the request
     * @param from
     *            -- the From header to assign to the request
     * @param to
     *            -- the To header to assign to the request
     * @return -- the newly generated sip request.
     */
    public SIPRequest createRequest(SipUri requestURI, Via via, CSeq cseq,
            From from, To to) {
        SIPRequest newRequest = new SIPRequest();
        String method = cseq.getMethod();

        newRequest.setMethod(method);
        newRequest.setRequestURI(requestURI);
        this.setBranch(via, method);
        newRequest.setHeader(via);
        newRequest.setHeader(cseq);
        newRequest.setHeader(from);
        newRequest.setHeader(to);
        newRequest.setHeader(getCallId());

        try {
            // JvB: all requests need a Max-Forwards
            newRequest.attachHeader(new MaxForwards(70), false);
        } catch (Exception d) {

        }

        if (MessageFactoryImpl.getDefaultUserAgentHeader() != null) {
            newRequest
                    .setHeader(MessageFactoryImpl.getDefaultUserAgentHeader());
        }
        return newRequest;

    }

    /**
     * Sets the Via branch for CANCEL or ACK requests
     * 
     * @param via
     * @param method
     * @throws ParseException
     */
    private final void setBranch(Via via, String method) {
        String branch;
        if (method.equals(Request.ACK)) {
            if (getLastResponseStatusCode().intValue() >= 300) {
                branch = lastResponseTopMostVia.getBranch(); // non-2xx ACK uses
                // same branch
            } else {
                branch = Utils.getInstance().generateBranchId(); // 2xx ACK gets
                // new branch
            }
        } else if (method.equals(Request.CANCEL)) {
            branch = lastResponseTopMostVia.getBranch(); // CANCEL uses same
            // branch
        } else
            return;

        try {
            via.setBranch(branch);
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#sendRequest(javax.sip.ClientTransaction)
     */

    public void sendRequest(ClientTransaction clientTransactionId)
            throws TransactionDoesNotExistException, SipException {
        this.sendRequest(clientTransactionId, !this.isBackToBackUserAgent);
    }

    public void sendRequest(ClientTransaction clientTransaction,
            boolean allowInterleaving) throws TransactionDoesNotExistException,
            SipException {

        if (clientTransaction == null)
            throw new NullPointerException("null parameter");
        
        
        if ((!allowInterleaving)
                && clientTransaction.getRequest().getMethod().equals(
                        Request.INVITE)) {
        	if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
        		logger.logDebug("SIPDialog::sendRequest " + this + " clientTransaction = " + clientTransaction);
        	}
        	
        	sipStack.getMessageProcessorExecutor().addTaskLast(
                    (new ReInviteSender(clientTransaction, getCallId().getCallId(), this)));
            return;
        }        

        SIPRequest dialogRequest = ((SIPClientTransaction) clientTransaction)
                .getOriginalRequest();

        this.proxyAuthorizationHeader = (ProxyAuthorization) dialogRequest
                .getHeader(ProxyAuthorizationHeader.NAME);

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
            logger.logDebug(
                    "SIPDialog::sendRequest:dialog.sendRequest " + " dialog = " + this
                            + "\ndialogRequest = \n" + dialogRequest);        

        if (dialogRequest.getMethod().equals(Request.ACK)
                || dialogRequest.getMethod().equals(Request.CANCEL))
            throw new SipException("Bad Request Method. "
                    + dialogRequest.getMethod());

        // JvB: added, allow re-sending of BYE after challenge
        if (byeSent && isTerminatedOnBye()
                && !dialogRequest.getMethod().equals(Request.BYE)) {
            if (logger.isLoggingEnabled())
                logger.logError(
                        "BYE already sent for " + this);
            throw new SipException("Cannot send request; BYE already sent");
        }

        if (dialogRequest.getTopmostVia() == null) {
            Via via = ((SIPClientTransaction) clientTransaction)
                    .getOutgoingViaHeader();
            dialogRequest.addHeader(via);
        }
        if (!this.getCallId().getCallId().equalsIgnoreCase(
                dialogRequest.getCallId().getCallId())) {

            if (logger.isLoggingEnabled()) {
                logger
                        .logError("CallID " + this.getCallId());
                logger.logError(
                        "SIPDialog::sendRequest:RequestCallID = "
                                + dialogRequest.getCallId().getCallId());
                logger.logError("dialog =  " + this);
            }
            throw new SipException("Bad call ID in request");
        }
        sipStack.getMessageProcessorExecutor().addTaskLast(
                    (new DialogOutgoingMessageProcessingTask(
                        this, (SIPClientTransaction) clientTransaction, dialogRequest)));
    }

    /**
     * Resend the last ack.
     */
    public void resendAck() throws SipException {
        // Check for null.

        if (this.lastAckSent != null) {
            
            SIPRequest lastAckSentParsed = lastAckSent.reparseRequest();
            if (lastAckSentParsed.getHeader(TimeStampHeader.NAME) != null
                    && sipStack.generateTimeStampHeader) {
                TimeStamp ts = new TimeStamp();
                try {
                    ts.setTimeStamp(System.currentTimeMillis());
                    lastAckSentParsed.setHeader(ts);
                } catch (InvalidArgumentException e) {

                }
            }
            this.sendAck(lastAckSentParsed, false);
        } else {
        	if(logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)){
        		logger.logDebug("SIPDialog::resendAck:lastAck sent is NULL hence not resending ACK");
        	}
        }

    }

    /**
     * Get the method of the request/response that resulted in the creation of
     * the Dialog.
     * 
     * @return -- the method of the dialog.
     */
    public String getMethod() {
        // Method of the request or response used to create this dialog
        return this.method;
    }

    /**
     * Start the dialog timer.
     * 
     * @param transaction
     */
    protected void startTimer(SIPServerTransaction transaction) {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
			logger.logDebug("Starting dialog timer for " + getDialogId() + ", timerTask=" + timerTask
					+ ", timerTaskStarted=" + timerTaskStarted.get() + ", ongoingTransactionId=" + ongoingTransactionId
					+ ", transaction= " + transaction.getTransactionId());

		if (this.timerTaskStarted.get() && ongoingTransactionId == transaction.getTransactionId()) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("Timer already running for " + getDialogId());
			return;
		}

		if (sipStack.getTimer() != null && sipStack.getTimer().isStarted()) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("setting  this.ongoingTransactionId " + this.ongoingTransactionId + " to "
						+ transaction.getTransactionId());
			this.ongoingTransactionId = transaction.getTransactionId();
			if (this.timerTaskStarted.compareAndSet(false, true)) {
				scheduleDialogTimer(transaction);
			}
		}
	}

    /**
     * Stop the dialog timer. This is called when the dialog is terminated.
     * 
     */
    protected void stopTimer() {
        stopDialogTimer();
        stopEarlyStateTimer();
        this.ongoingTransactionId = null;                    
    }

    protected void scheduleDialogTimer(SIPServerTransaction transaction) {
		if (this.timerTask.get() != null)
			return;

		SIPDialogTimerTask newTimer = new SIPDialogTimerTask(this, transaction, transaction.getTimerT2(),
				transaction.getBaseTimerInterval(), transaction.getBaseTimerInterval(), 0);
		if (this.timerTask.compareAndSet(null, newTimer)) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("Executing DialogTimerTask " + timerTask + " with fixed delay and period of "
						+ transaction.getBaseTimerInterval());
			sipStack.getTimer().schedule(newTimer, transaction.getBaseTimerInterval());
		}
	}

    protected void rescheduleDialogTimer(int timerT2, long baseTimerInterval, long currentInterval, long summaryInterval, SIPServerTransaction originalTransaction) {
		stopDialogTimer();

		SIPDialogTimerTask newTimer = new SIPDialogTimerTask(this, originalTransaction, timerT2,
				baseTimerInterval, currentInterval, summaryInterval);
		if (this.timerTask.compareAndSet(null, newTimer)) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("Executing DialogTimerTask " + timerTask + " with delay and period of "
						+ currentInterval);
			sipStack.getTimer().schedule(newTimer, currentInterval);
		}
	}

    protected void stopDialogTimer() {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("Trying to stop Dialog Timers " + this.timerTask);
		}

		SIPDialogTimerTask oldTask = this.timerTask.getAndSet(null);
		if (oldTask != null) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("Cancelled Dialog Timer " + timerTask);
			}
			this.getStack().getTimer().cancel(oldTask);
		}
	}
    
    /*
     * (non-Javadoc) Retransmissions of the reliable provisional response cease
     * when a matching PRACK is received by the UA core. PRACK is like any other
     * request within a dialog, and the UAS core processes it according to the
     * procedures of Sections 8.2 and 12.2.2 of RFC 3261. A matching PRACK is
     * defined as one within the same dialog as the response, and whose method,
     * CSeq-num, and response-num in the RAck header field match, respectively,
     * the method from the CSeq, the sequence number from the CSeq, and the
     * sequence number from the RSeq of the reliable provisional response.
     * 
     * @see javax.sip.Dialog#createPrack(javax.sip.message.Response)
     */
    public Request createPrack(Response relResponse)
            throws DialogDoesNotExistException, SipException {

        if (this.getState() == null
                || this.getState().equals(DialogState.TERMINATED))
            throw new DialogDoesNotExistException(
                    "Dialog not initialized or terminated");

        if ((RSeq) relResponse.getHeader(RSeqHeader.NAME) == null) {
            throw new SipException("Missing RSeq Header");
        }

        try {
            SIPResponse sipResponse = (SIPResponse) relResponse;
            SIPRequest sipRequest = this.createRequest(Request.PRACK,
                    sipResponse.getTopmostVia().getTransport());
            String toHeaderTag = sipResponse.getTo().getTag();
            sipRequest.setToTag(toHeaderTag);
            RAck rack = new RAck();
            RSeq rseq = (RSeq) relResponse.getHeader(RSeqHeader.NAME);
            rack.setMethod(sipResponse.getCSeq().getMethod());
            rack.setCSequenceNumber((int) sipResponse.getCSeq().getSeqNumber());
            rack.setRSequenceNumber(rseq.getSeqNumber());
            sipRequest.setHeader(rack);
            if (this.proxyAuthorizationHeader != null) {
                sipRequest.addHeader(proxyAuthorizationHeader);
            }
            return (Request) sipRequest;
        } catch (Exception ex) {
            InternalErrorHandler.handleException(ex);
            return null;
        }

    }

    private void updateRequest(SIPRequest sipRequest) {

        RouteList rl = this.getRouteList();
        if (rl.size() > 0) {
            sipRequest.setHeader(rl);
        } else {
            sipRequest.removeHeader(RouteHeader.NAME);
        }
        if (MessageFactoryImpl.getDefaultUserAgentHeader() != null) {
            sipRequest
                    .setHeader(MessageFactoryImpl.getDefaultUserAgentHeader());
        }

        /*
         * Update the request with Proxy auth header if one has been cached.
         */
        if (this.proxyAuthorizationHeader != null
                && sipRequest.getHeader(ProxyAuthorizationHeader.NAME) == null) {
            sipRequest.setHeader(proxyAuthorizationHeader);
        }

    }

    /*
     * (non-Javadoc) The UAC core MUST generate an ACK request for each 2xx
     * received from the transaction layer. The header fields of the ACK are
     * constructed in the same way as for any request sent within a dialog (see
     * Section 12) with the exception of the CSeq and the header fields related
     * to authentication. The sequence number of the CSeq header field MUST be
     * the same as the INVITE being acknowledged, but the CSeq method MUST be
     * ACK. The ACK MUST contain the same credentials as the INVITE. If the 2xx
     * contains an offer (based on the rules above), the ACK MUST carry an
     * answer in its body. If the offer in the 2xx response is not acceptable,
     * the UAC core MUST generate a valid answer in the ACK and then send a BYE
     * immediately.
     * 
     * Note that for the case of forked requests, you can create multiple
     * outgoing invites each with a different cseq and hence you need to supply
     * the invite.
     * 
     * @see javax.sip.Dialog#createAck(long)
     */
    public Request createAck(long cseqno) throws InvalidArgumentException,
            SipException {

        // JvB: strictly speaking it is allowed to start a dialog with
        // SUBSCRIBE,
        // then send INVITE+ACK later on
        if (!method.equals(Request.INVITE))
            throw new SipException("Dialog was not created with an INVITE"
                    + method);

        if (cseqno <= 0)
            throw new InvalidArgumentException("bad cseq <= 0 ");
        else if (cseqno > ((((long) 1) << 32) - 1))
            throw new InvalidArgumentException("bad cseq > "
                    + ((((long) 1) << 32) - 1));

        if (this.getRemoteTarget() == null) {
            throw new SipException("Cannot create ACK - no remote Target!");
        }

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "createAck " + this + " cseqno " + cseqno);
        }

        // MUST ack in the same order that the OKs were received. This traps
        // out of order ACK sending. Old ACKs seqno's can always be ACKed.
        if (lastInviteOkReceived < cseqno) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "WARNING : Attempt to crete ACK without OK " + this);
                logger.logDebug(
                        "LAST RESPONSE = " + this.getLastResponseStatusCode());
            }
            throw new SipException(
            		"Dialog not yet established -- no OK response! lastInviteOkReceived=" + 
            		                    lastInviteOkReceived + " cseqno=" + cseqno);
        }

        try {

            // JvB: Transport from first entry in route set, or remote Contact
            // if none
            // Only used to find correct LP & create correct Via
            SipURI uri4transport = null;

            if (this.routeList != null && !this.routeList.isEmpty()) {
                Route r = (Route) this.routeList.getFirst();
                uri4transport = ((SipURI) r.getAddress().getURI());
            } else { // should be !=null, checked above
                uri4transport = ((SipURI) this.getRemoteTarget().getURI());
            }

            String transport = uri4transport.getTransportParam();
            ListeningPointImpl lp;
            
            if(uri4transport.isSecure()){
            	// Fix for https://java.net/jira/browse/JSIP-492
            	if(transport != null && transport.equalsIgnoreCase(ListeningPoint.UDP)){
            		throw new SipException("Cannot create ACK - impossible to use sips uri with transport UDP:"+uri4transport);
            	}
            	transport = ListeningPoint.TLS;
            }
            if (transport != null) {
                lp = (ListeningPointImpl) sipProvider
                        .getListeningPoint(transport);
            } else {
                if (uri4transport.isSecure()) { // JvB fix: also support TLS
                    lp = (ListeningPointImpl) sipProvider
                            .getListeningPoint(ListeningPoint.TLS);
                } else {
                    lp = (ListeningPointImpl) sipProvider
                            .getListeningPoint(ListeningPoint.UDP);
                    if (lp == null) { // Alex K fix: let's try to find TCP
                        lp = (ListeningPointImpl) sipProvider
                                .getListeningPoint(ListeningPoint.TCP);
                    }
                }
            }
            
            
            if ( logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                logger.logDebug("uri4transport =  " + uri4transport);
            }
            
            if (lp == null) {
                if ( ! uri4transport.isSecure()) {
                    // If transport is not secure, and we cannot find an appropriate transport, try any supported transport to send out the ACK.
                    if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
                        logger.logDebug(
                                "No Listening point for " + uri4transport + " Using last response topmost" ); 
                    }
                    // We are not on a secure connection and we don't support the transport required 
                    lp = (ListeningPointImpl) sipProvider.getListeningPoint(this.lastResponseTopMostVia.getTransport());
                }
                
              
                
                if ( lp== null) {
                    if (logger.isLoggingEnabled(LogLevels.TRACE_ERROR)) {
                        logger.logError(
                                "remoteTargetURI "
                                + this.getRemoteTarget().getURI());
                        logger.logError(
                                "uri4transport = " + uri4transport);
                        logger.logError(
                                "No LP found for transport=" + transport);
                    }
                    throw new SipException(
                            "Cannot create ACK - no ListeningPoint for transport towards next hop found:"
                            + transport);
                }

            }
            SIPRequest sipRequest = new SIPRequest();
            sipRequest.setMethod(Request.ACK);
            sipRequest.setRequestURI((SipUri) getRemoteTarget().getURI()
                    .clone());
            sipRequest.setCallId(this.getCallId());
            sipRequest.setCSeq(new CSeq(cseqno, Request.ACK));
            List<Via> vias = new ArrayList<Via>();
            // Via via = lp.getViaHeader();
            // The user may have touched the sentby for the response.
            // so use the via header extracted from the response for the ACK =>
            // https://jain-sip.dev.java.net/issues/show_bug.cgi?id=205
            // strip the params from the via of the response and use the params
            // from the
            // original request
            Via via = this.lastResponseTopMostVia;
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug("lastResponseTopMostVia " + lastResponseTopMostVia);
            }
            via.removeParameters();
            if (originalRequest != null
                    && originalRequest.getTopmostVia() != null) {
                NameValueList originalRequestParameters = originalRequest
                        .getTopmostVia().getParameters();
                if (originalRequestParameters != null
                        && originalRequestParameters.size() > 0) {
                    via.setParameters((NameValueList) originalRequestParameters
                            .clone());
                }
            }
            via.setBranch(Utils.getInstance().generateBranchId()); // new branch
            vias.add(via);
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug("Adding via to the ACK we are creating : " + via + " lastResponseTopMostVia " + lastResponseTopMostVia);
            }     
            sipRequest.setVia(vias);
            
            From from = new From();
            from.setAddress(this.getLocalParty());
            from.setTag(this.myTag);
            sipRequest.setFrom(from);
            To to = new To();
            to.setAddress(this.getRemoteParty());
            if (hisTag != null)
                to.setTag(this.hisTag);
            sipRequest.setTo(to);
            sipRequest.setMaxForwards(new MaxForwards(70));

            if (this.originalRequest != null) {
                Authorization authorization = this.originalRequest
                        .getAuthorization();
                if (authorization != null)
                    sipRequest.setHeader(authorization);
                // jeand : setting back the original Request to null to avoid
                // keeping references around for too long
                // since it is used only in the dialog setup
                originalRequestRecordRouteHeaders = originalRequest
                        .getRecordRouteHeaders();
                originalRequest = null;
            }

            // ACKs for 2xx responses
            // use the Route values learned from the Record-Route of the 2xx
            // responses.
            this.updateRequest(sipRequest);

            return sipRequest;
        } catch (Exception ex) {
            InternalErrorHandler.handleException(ex);
            throw new SipException("unexpected exception ", ex);
        }

    }

    /**
     * Get the provider for this Dialog.
     * 
     * SPEC_REVISION
     * 
     * @return -- the SIP Provider associated with this transaction.
     */
    public SipProviderImpl getSipProvider() {
        return this.sipProvider;
    }

    /**
     * @param sipProvider
     *            the sipProvider to set
     */
    public void setSipProvider(SipProviderImpl sipProvider) {
        this.sipProvider = sipProvider;
    }

    /**
     * Check the tags of the response against the tags of the Dialog. Return
     * true if the respnse matches the tags of the dialog. We do this check wehn
     * sending out a response.
     * 
     * @param sipResponse
     *            -- the response to check.
     * 
     */
    public void setResponseTags(SIPResponse sipResponse) {
        if (this.getLocalTag() != null || this.getRemoteTag() != null) {
            return;
        }
        String responseFromTag = sipResponse.getFromTag();
        if (responseFromTag != null) {
            if (responseFromTag.equals(this.getLocalTag())) {
                sipResponse.setToTag(this.getRemoteTag());
            } else if (responseFromTag.equals(this.getRemoteTag())) {
                sipResponse.setToTag(this.getLocalTag());
            }
        } else {
            if (logger.isLoggingEnabled())
                logger.logWarning(
                        "No from tag in response! Not RFC 3261 compatible.");
        }

    }
    
    /**
     * Set the last response for this dialog. This method is called for updating
     * the dialog state when a response is either sent or received from within a
     * Dialog.
     * 
     * @param transaction
     *            -- the transaction associated with the response
     * @param sipResponse
     *            -- the last response to set.
     */
    public void setLastResponse(SIPTransaction transaction,
            SIPResponse sipResponse) {
        this.callIdHeader = sipResponse.getCallId();
        final int statusCode = sipResponse.getStatusCode();
        if (statusCode == 100) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger
                        .logDebug(
                                "Invalid status code - 100 in setLastResponse - ignoring");
            return;
        }

        if ( logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
        	logger.logStackTrace();
        }
        // this.lastResponse = sipResponse;
        try {
            this.lastResponseStatusCode = Integer.valueOf(statusCode);
            // Issue 378 : http://java.net/jira/browse/JSIP-378
            // Cloning the via header to avoid race condition and be modified
            this.lastResponseTopMostVia = (Via) sipResponse.getTopmostVia().clone();            
            String cseqMethod = sipResponse.getCSeqHeader().getMethod();
            this.lastResponseMethod = cseqMethod;
            long responseCSeqNumber = sipResponse.getCSeq().getSeqNumber();
            
            boolean is100ClassResponse = statusCode / 100 == 1;
            boolean is200ClassResponse = statusCode / 100 == 2;
            
            this.lastResponseCSeqNumber = responseCSeqNumber;
            if(Request.INVITE.equals(cseqMethod)) {
            	this.lastInviteResponseCSeqNumber = responseCSeqNumber;
            	this.lastInviteResponseCode = statusCode;
            }
            if (sipResponse.getToTag() != null ) {
                this.lastResponseToTag = sipResponse.getToTag();
            }
            if ( sipResponse.getFromTag() != null ) {
                this.lastResponseFromTag = sipResponse.getFromTag();
            }
            if (transaction != null) {
                this.lastResponseDialogId = sipResponse.getDialogId(transaction
                        .isServerTransaction());
            }
            this.setAssigned();
            // Adjust state of the Dialog state machine.
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "sipDialog: setLastResponse:" + this
                                + " lastResponse = "
                                + this.lastResponseStatusCode 
                                + " response " + sipResponse.toString()
                                + " topMostViaHeader " + lastResponseTopMostVia);
            }
            if (this.getState() == DialogState.TERMINATED) {
                if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                    logger
                            .logDebug(
                                    "sipDialog: setLastResponse -- dialog is terminated - ignoring ");
                }
                // Capture the OK response for later use in createAck
                // This is handy for late arriving OK's that we want to ACK.
                if (cseqMethod.equals(Request.INVITE)
                        && statusCode == 200) {

                    this.lastInviteOkReceived = Math.max(
                    		responseCSeqNumber, this.lastInviteOkReceived);
                }
                return;
            }
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logStackTrace();
                logger.logDebug(
                        "cseqMethod = " + cseqMethod);
                logger.logDebug(
                        "dialogState = " + this.getState());
                logger.logDebug(
                        "method = " + this.getMethod());
                logger
                        .logDebug("statusCode = " + statusCode);
                logger.logDebug(
                        "transaction = " + transaction);
            }

            // JvB: don't use "!this.isServer" here
            // note that the transaction can be null for forked
            // responses.
            if (transaction == null || transaction instanceof ClientTransaction) {
                if (SIPTransactionStack.isDialogCreatingMethod(cseqMethod)) {
                    // Make a final tag assignment.
                    if (getState() == null && is100ClassResponse) {
                        /*
                         * Guard aginst slipping back into early state from
                         * confirmed state.
                         */
                        setState(SIPDialog.EARLY_STATE);
                        if (sipResponse.getToTag() != null && this.getRemoteTag() == null) {
                            setRemoteTag(sipResponse.getToTag());
                            this.setDialogId(sipResponse.getDialogId(false));
                            sipStack.putDialog(this);
                            this.addRoute(sipResponse);
                        }
                    } else if (getState() != null
                            && getState().equals(DialogState.EARLY)
                            && is100ClassResponse) {
                        /*
                         * This case occurs for forked dialog responses. The To
                         * tag can change as a result of the forking. The remote
                         * target can also change as a result of the forking.
                         */
                        if (cseqMethod.equals(getMethod())
                                && transaction != null && sipResponse.getToTag() != null) {
                            setRemoteTag(sipResponse.getToTag());
                            this.setDialogId(sipResponse.getDialogId(false));
                            sipStack.putDialog(this);
                            this.addRoute(sipResponse);
                        }
                    } else if (is200ClassResponse) {
                        // This is a dialog creating method (such as INVITE).
                        // 2xx response -- set the state to the confirmed
                        // state. To tag is MANDATORY for the response.

                        // Only do this if method equals initial request!
                        if (logger.isLoggingEnabled(
                                LogWriter.TRACE_DEBUG)) {
                            logger
                                    .logDebug(
                                            "pendingRouteUpdateOn202Response : "
                                                    + this.pendingRouteUpdateOn202Response);
                        }
                        if (cseqMethod.equals(getMethod())
                                && sipResponse.getToTag() != null
                                && (this.getState() != DialogState.CONFIRMED || (this
                                        .getState() == DialogState.CONFIRMED
                                        && cseqMethod
                                                .equals(Request.SUBSCRIBE)
                                        && this.pendingRouteUpdateOn202Response && 
                                is200ClassResponse))) {
                            if (this.getState() != DialogState.CONFIRMED) {
                                setRemoteTag(sipResponse.getToTag());
                                this
                                        .setDialogId(sipResponse
                                                .getDialogId(false));
                                sipStack.putDialog(this);
                                this.addRoute(sipResponse);
                                this.setState(CONFIRMED_STATE);
                            }

                            /*
                             * Note: Subscribe NOTIFY processing. The route set
                             * is computed only after we get the 202 response
                             * but the NOTIFY may come in before we get the 202
                             * response. So we need to update the route set
                             * after we see the 202 despite the fact that the
                             * dialog is in the CONFIRMED state. We do this only
                             * on the dialog forming SUBSCRIBE an not a
                             * resubscribe.
                             */

                            if (cseqMethod.equals(Request.SUBSCRIBE)
                                    && is200ClassResponse
                                    && this.pendingRouteUpdateOn202Response) {
                                setRemoteTag(sipResponse.getToTag());
                                this.addRoute(sipResponse);
                                this.pendingRouteUpdateOn202Response = false;
                            }
                        }

                        // Capture the OK response for later use in createAck
                        if (cseqMethod.equals(Request.INVITE)) {
                            this.lastInviteOkReceived = Math.max(responseCSeqNumber,
                                    this.lastInviteOkReceived);
                            if(getState() != null && getState().getValue() == SIPDialog.CONFIRMED_STATE && transaction != null) {
                            	// http://java.net/jira/browse/JSIP-444 Honor Target Refresh on Response
                            	// Contribution from francoisjoseph levee (Orange Labs)
                            	doTargetRefresh(sipResponse);
                            }
                        }

                    } else if (statusCode >= 300
                            && statusCode <= 699
                            && (getState() == null || (cseqMethod
                                    .equals(getMethod()) && getState()
                                    .getValue() == SIPDialog.EARLY_STATE))) {
                        /*
                         * This case handles 3xx, 4xx, 5xx and 6xx responses.
                         * RFC 3261 Section 12.3 - dialog termination.
                         * Independent of the method, if a request outside of a
                         * dialog generates a non-2xx final response, any early
                         * dialogs created through provisional responses to that
                         * request are terminated.
                         */
                        setState(SIPDialog.TERMINATED_STATE);
                    }

                    /*
                     * This code is in support of "proxy" servers that are
                     * constructed as back to back user agents. This could be a
                     * dialog in the middle of the call setup path somewhere.
                     * Hence the incoming invite has record route headers in it.
                     * The response will have additional record route headers.
                     * However, for this dialog only the downstream record route
                     * headers matter. Ideally proxy servers should not be
                     * constructed as Back to Back User Agents. Remove all the
                     * record routes that are present in the incoming INVITE so
                     * you only have the downstream Route headers present in the
                     * dialog. Note that for an endpoint - you will have no
                     * record route headers present in the original request so
                     * the loop will not execute.
                     */
                    if (this.getState() != DialogState.CONFIRMED
                            && this.getState() != DialogState.TERMINATED) {
                        if (getOriginalRequestRecordRouteHeaders() != null) {
                            ListIterator<RecordRoute> it = getOriginalRequestRecordRouteHeaders()
                                    .listIterator(
                                            getOriginalRequestRecordRouteHeaders()
                                                    .size());
                            while (it.hasPrevious()) {
                                RecordRoute rr = (RecordRoute) it.previous();
                                Route route = (Route) routeList.getFirst();
                                if (route != null
                                        && rr.getAddress().equals(
                                                route.getAddress())) {
                                    routeList.removeFirstItem();
                                } else
                                    break;
                            }
                        }
                    }

                } else if (cseqMethod.equals(Request.NOTIFY)
                        && (this.getMethod().equals(Request.SUBSCRIBE) || this
                                .getMethod().equals(Request.REFER))
                        && is200ClassResponse
                        && this.getState() == null) {
                    // This is a notify response.
                    this.setDialogId(sipResponse.getDialogId(true));
                    sipStack.putDialog(this);
                    this.setState(SIPDialog.CONFIRMED_STATE);

                } else if (cseqMethod.equals(Request.BYE)
                        && is200ClassResponse && isTerminatedOnBye()) {
                    // Dialog will be terminated when the transction is
                    // terminated.
                    setState(SIPDialog.TERMINATED_STATE);
                }
            } else {
                // Processing Server Dialog.

                if (cseqMethod.equals(Request.BYE)
                        && is200ClassResponse && this.isTerminatedOnBye()) {
                    /*
                     * Only transition to terminated state when 200 OK is
                     * returned for the BYE. Other status codes just result in
                     * leaving the state in COMPLETED state.
                     */
                    this.setState(SIPDialog.TERMINATED_STATE);                    
                } else {
                    boolean doPutDialog = false;

                    if (getLocalTag() == null
                            && sipResponse.getTo().getTag() != null
                            && SIPTransactionStack.isDialogCreatingMethod(cseqMethod)
                            && cseqMethod.equals(getMethod())) {
                        setLocalTag(sipResponse.getTo().getTag());
                        doPutDialog = true;
                    }

                    if (!is200ClassResponse) {
                        if (is100ClassResponse) {
                            if (doPutDialog) {

                                setState(SIPDialog.EARLY_STATE);
                                this.setDialogId(sipResponse.getDialogId(true));
                                sipStack.putDialog(this);
                            }
                        } else {
                            /*
                             * RFC 3265 chapter 3.1.4.1 "Non-200 class final
                             * responses indicate that no subscription or dialog
                             * has been created, and no subsequent NOTIFY
                             * message will be sent. All non-200 class" +
                             * responses (with the exception of "489", described
                             * herein) have the same meanings and handling as
                             * described in SIP"
                             */
                            // Bug Fix by Jens tinfors
                            // see
                            // https://jain-sip.dev.java.net/servlets/ReadMsg?list=users&msgNo=797
                            if (statusCode == 489
                                    && (cseqMethod
                                            .equals(Request.NOTIFY) || cseqMethod
                                            .equals(Request.SUBSCRIBE))) {
                                if (logger
                                        .isLoggingEnabled(LogWriter.TRACE_DEBUG))
                                    logger
                                            .logDebug(
                                                    "RFC 3265 : Not setting dialog to TERMINATED for 489");
                            } else {
                                // baranowb: simplest fix to
                                // https://jain-sip.dev.java.net/issues/show_bug.cgi?id=175
                                // application is responsible for terminating in
                                // this case
                                // see rfc 5057 for better explanation
                                if (!this.isReInvite()
                                        && getState() != DialogState.CONFIRMED) {
                                    this.setState(SIPDialog.TERMINATED_STATE);
                                }
                            }
                        }

                    } else {

                        /*
                         * JvB: RFC4235 says that when sending 2xx on UAS side,
                         * state should move to CONFIRMED
                         */
                        if (this.dialogState.get() <= SIPDialog.EARLY_STATE
                                && (cseqMethod.equals(Request.INVITE)
                                        || cseqMethod
                                                .equals(Request.SUBSCRIBE) || cseqMethod
                                        .equals(Request.REFER))) {
                            this.setState(SIPDialog.CONFIRMED_STATE);
                        }

                        if (doPutDialog) {
                            this.setDialogId(sipResponse.getDialogId(true));
                            sipStack.putDialog(this);
                        }
                        
                    }
                }

            }
        } finally {
            if (sipResponse.getCSeq().getMethod().equals(Request.INVITE) &&
                    transaction != null && transaction instanceof ClientTransaction && this.getState() != DialogState.TERMINATED) {
                // this.acquireTimerTaskSem();
                // try {
                    if (this.getState() == DialogState.EARLY) {
                        stopEarlyStateTimer();
                        startEarlyStateTimer();
                    } else {
                        stopEarlyStateTimer();
                    }
                // } finally {
                //     this.releaseTimerTaskSem();
                // }
            }
        }

    }

    /**
     * Start the retransmit timer.
     * 
     * @param sipServerTx
     *            -- server transaction on which the response was sent
     * @param response
     *            - response that was sent.
     */
    public void startRetransmitTimer(SIPServerTransaction sipServerTx,
            Response response) {        
        if (sipServerTx.isInviteTransaction()
                && response.getStatusCode() / 100 == 2) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "starting Retransmit Timer " + response.getStatusCode()
                                + " method " + sipServerTx.getMethod());
            }
            this.startTimer(sipServerTx);
        } else {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "not starting Retransmit Timer " + response.getStatusCode()
                                + " method " + sipServerTx.getMethod());
            }
        }
    }

    /**
     * @return -- the last response associated with the dialog.
     */
    // public SIPResponse getLastResponse() {
    //
    // return lastResponse;
    // }
    /**
     * Do taget refresh dialog state updates.
     * 
     * RFC 3261: Requests within a dialog MAY contain Record-Route and Contact
     * header fields. However, these requests do not cause the dialog's route
     * set to be modified, although they may modify the remote target URI.
     * Specifically, requests that are not target refresh requests do not modify
     * the dialog's remote target URI, and requests that are target refresh
     * requests do. For dialogs that have been established with an
     * 
     * INVITE, the only target refresh request defined is re-INVITE (see Section
     * 14). Other extensions may define different target refresh requests for
     * dialogs established in other ways.
     */
    private void doTargetRefresh(SIPMessage sipMessage) {

        ContactList contactList = sipMessage.getContactHeaders();

        /*
         * INVITE is the target refresh for INVITE dialogs. SUBSCRIBE is the
         * target refresh for subscribe dialogs from the client side. This
         * modifies the remote target URI potentially
         */
        if (contactList != null) {

            Contact contact = (Contact) contactList.getFirst();
            this.setRemoteTarget(contact);

        }

    }

    private static final boolean optionPresent(ListIterator<SIPHeader> l, String option) {
        while (l.hasNext()) {
            OptionTag opt = (OptionTag) l.next();
            if (opt != null && option.equalsIgnoreCase(opt.getOptionTag()))
                return true;
        }
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#createReliableProvisionalResponse(int)
     */
    public Response createReliableProvisionalResponse(int statusCode)
            throws InvalidArgumentException, SipException {

        if (!(firstTransactionIsServerTransaction)) {
            throw new SipException("Not a Server Dialog!");

        }
        /*
         * A UAS MUST NOT attempt to send a 100 (Trying) response reliably. Only
         * provisional responses numbered 101 to 199 may be sent reliably. If
         * the request did not include either a Supported or Require header
         * field indicating this feature, the UAS MUST NOT send the provisional
         * response reliably.
         */
        if (statusCode <= 100 || statusCode > 199)
            throw new InvalidArgumentException("Bad status code ");
        SIPRequest request = this.originalRequest;
        if (!request.getMethod().equals(Request.INVITE))
            throw new SipException("Bad method");

        ListIterator<SIPHeader> list = request.getHeaders(SupportedHeader.NAME);
        if (list == null || !optionPresent(list, "100rel")) {
            list = request.getHeaders(RequireHeader.NAME);
            if (list == null || !optionPresent(list, "100rel")) {
                throw new SipException(
                        "No Supported/Require 100rel header in the request");
            }
        }

        SIPResponse response = request.createResponse(statusCode);
        /*
         * The provisional response to be sent reliably is constructed by the
         * UAS core according to the procedures of Section 8.2.6 of RFC 3261. In
         * addition, it MUST contain a Require header field containing the
         * option tag 100rel, and MUST include an RSeq header field. The value
         * of the header field for the first reliable provisional response in a
         * transaction MUST be between 1 and 231 - 1. It is RECOMMENDED that it
         * be chosen uniformly in this range. The RSeq numbering space is within
         * a single transaction. This means that provisional responses for
         * different requests MAY use the same values for the RSeq number.
         */
        Require require = new Require();
        try {
            require.setOptionTag("100rel");
        } catch (Exception ex) {
            InternalErrorHandler.handleException(ex);
        }
        response.addHeader(require);
        RSeq rseq = new RSeq();
        /*
         * set an arbitrary sequence number. This is actually set when the
         * response is sent out
         */
        rseq.setSeqNumber(1L);
        /*
         * Copy the record route headers from the request to the response (
         * Issue 160 ). Note that other 1xx headers do not get their Record
         * Route headers copied over but reliable provisional responses do. See
         * RFC 3262 Table 2.
         */
        RecordRouteList rrl = request.getRecordRouteHeaders();
        if (rrl != null) {
            RecordRouteList rrlclone = (RecordRouteList) rrl.clone();
            response.setHeader(rrlclone);
        }

        return response;
    }

    /**
     * Do the processing necessary for the PRACK
     * 
     * @param prackRequest
     * @return true if this is the first time the tx has seen the prack ( and
     *         hence needs to be passed up to the TU)
     */
    public boolean handlePrack(SIPRequest prackRequest) {
        /*
         * The RAck header is sent in a PRACK request to support reliability of
         * provisional responses. It contains two numbers and a method tag. The
         * first number is the value from the RSeq header in the provisional
         * response that is being acknowledged. The next number, and the method,
         * are copied from the CSeq in the response that is being acknowledged.
         * The method name in the RAck header is case sensitive.
         */
        if (!this.isServer()) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "Dropping Prack -- not a server Dialog");
            return false;
        }
        SIPServerTransaction sipServerTransaction = (SIPServerTransaction) this
                .getFirstTransactionInt();
        byte[] sipResponse = pendingReliableResponseAsBytes;

        if (sipResponse == null) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "Dropping Prack -- ReliableResponse not found in STX " + sipServerTransaction + " for dialog ID " + dialogId + " dialog " + this);
            return false;
        }

        RAck rack = (RAck) prackRequest.getHeader(RAckHeader.NAME);

        if (rack == null) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "Dropping Prack -- rack header not found");
            return false;
        }

        if (!rack.getMethod().equals(pendingReliableResponseMethod)) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "Dropping Prack -- CSeq Header does not match PRACK");
            return false;
        }

        if (rack.getCSeqNumberLong() != pendingReliableCSeqNumber) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "Dropping Prack -- CSeq Header does not match PRACK");
            return false;
        }

        if (rack.getRSequenceNumber() != pendingReliableRSeqNumber) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "Dropping Prack -- RSeq Header does not match PRACK");
            return false;
        }

        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug("prackReceived " + prackRequest + " for STX " + this + " reliableResponse " + String.valueOf(pendingReliableResponseAsBytes));

        if (this.pendingReliableResponseAsBytes == null)
            return false;
        stopReliableResponseTimer();
        
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
            logger.logDebug("cleaning reliableResponse " + pendingReliableResponseAsBytes + " for STX " + this + " after PRACK Received");

        this.pendingReliableResponseAsBytes = null;
        
        // if (interlockProvisionalResponses && getDialog() != null) {
        //     this.provisionalResponseSem.release();
        // }
        return true;         
    }     

    /*
     * (non-Javadoc)
     * 
     * @see
     * javax.sip.Dialog#sendReliableProvisionalResponse(javax.sip.message.Response
     * )
     */
    public void sendReliableProvisionalResponse(Response relResponse)
            throws SipException {
        if (!this.isServer()) {
            throw new SipException("Not a Server Dialog");
        }

        SIPResponse sipResponse = (SIPResponse) relResponse;

        if (relResponse.getStatusCode() == 100)
            throw new SipException(
                    "Cannot send 100 as a reliable provisional response");

        if (relResponse.getStatusCode() / 100 > 2)
            throw new SipException(
                    "Response code is not a 1xx response - should be in the range 101 to 199 ");

        /*
         * Do a little checking on the outgoing response.
         */
        if (sipResponse.getToTag() == null) {
            throw new SipException(
                    "Badly formatted response -- To tag mandatory for Reliable Provisional Response");
        }
        ListIterator<?> requireList = relResponse.getHeaders(RequireHeader.NAME);
        boolean found = false;

        if (requireList != null) {

            while (requireList.hasNext() && !found) {
                RequireHeader rh = (RequireHeader) requireList.next();
                if (rh.getOptionTag().equalsIgnoreCase("100rel")) {
                    found = true;
                }
            }
        }

        if (!found) {
            Require require = new Require("100rel");
            relResponse.addHeader(require);
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger
                        .logDebug(
                                "Require header with optionTag 100rel is needed -- adding one");
            }

        }

        SIPServerTransactionImpl serverTransaction = (SIPServerTransactionImpl) this
                .getFirstTransactionInt();
        /*
         * put into the dialog table before sending the response so as to avoid
         * race condition with PRACK
         */
        this.setLastResponse(serverTransaction, sipResponse);

        this.setDialogId(sipResponse.getDialogId(true));

        // serverTransaction.sendReliableProvisionalResponse(relResponse);
        /*
         * After the first reliable provisional response for a request has been
         * acknowledged, the
         * UAS MAY send additional reliable provisional responses. The UAS MUST NOT send
         * a second
         * reliable provisional response until the first is acknowledged.
         */
        if (this.pendingReliableResponseAsBytes != null) {
            throw new SipException("Unacknowledged response");

        } 

        SIPDialogOutgoingProvisionalResponseTask outgoingMessageProcessingTask = 
            new SIPDialogOutgoingProvisionalResponseTask(this, serverTransaction, (SIPResponse) relResponse);
        sipStack.getMessageProcessorExecutor().addTaskLast(outgoingMessageProcessingTask); 

        this.startRetransmitTimer(serverTransaction, relResponse);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.Dialog#terminateOnBye(boolean)
     */
    public void terminateOnBye(boolean terminateFlag) throws SipException {

        this.terminateOnBye = terminateFlag;
    }

    /**
     * Set the "assigned" flag to true. We do this when inserting the dialog
     * into the dialog table of the stack.
     * 
     */
    public void setAssigned() {
        this.isAssigned = true;
    }

    /**
     * Return true if the dialog has already been mapped to a transaction.
     * 
     */

    public boolean isAssigned() {
        return this.isAssigned;
    }

    /**
     * Get the contact header that the owner of this dialog assigned. Subsequent
     * Requests are considered to belong to the dialog if the dialog identifier
     * matches and the contact header matches the ip address and port on which
     * the request is received.
     * 
     * @return contact header belonging to the dialog.
     */
    public Contact getMyContactHeader() {
        if (contactHeader == null && contactHeaderStringified != null) {
            try {
                this.contactHeader = (Contact) new ContactParser(
                        contactHeaderStringified).parse();
            } catch (ParseException e) {
                logger.logError(
                        "error reparsing the contact header", e);
            }
        }
        return contactHeader;
    }

    /**
     * Do the necessary processing to handle an ACK directed at this Dialog.
     * 
     * @param ackTransaction
     *            -- the ACK transaction that was directed at this dialog.
     * @return -- true if the ACK was successfully consumed by the Dialog and
     *         resulted in the dialog state being changed.
     */
    public boolean handleAck(SIPServerTransaction ackTransaction) {
        // SIPRequest sipRequest = ackTransaction.getOriginalRequest();

        if (isAckSeen() && getRemoteSeqNumber() == ackTransaction.getCSeq()) {

            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "SIPDialog::handleAck: ACK already seen by dialog -- dropping Ack"
                                + " retransmission");
            }
            // acquireTimerTaskSem();
            // try {
                stopTimer();
            // } finally {
            //     releaseTimerTaskSem();
            // }
            return false;
        } else if (this.getState() == DialogState.TERMINATED) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                logger.logDebug(
                        "SIPDialog::handleAck: Dialog " + getDialogId() + " is terminated -- dropping ACK");
            return false;

        } else {
        	  if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
        		  logger.logDebug("SIPDialog::handleAck: lastResponseCSeqNumber = " + lastInviteOkReceived + " ackTxCSeq " + ackTransaction.getCSeq());
        	  }
             if (lastResponseStatusCode != null
                    && this.lastInviteResponseCode / 100 == 2
                    && lastInviteResponseCSeqNumber == ackTransaction.getCSeq()) {

                ackTransaction.setDialog(this, lastResponseDialogId);
                /*
                 * record that we already saw an ACK for this dialog.
                 */
                ackReceived(ackTransaction.getCSeq());
                if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                    logger.logDebug(
                            "SIPDialog::handleACK: ACK for 2XX response --- sending to TU ");
                return true;

            } else {
               	/*
                 * This happens when the ACK is re-transmitted and arrives too
                 * late to be processed.
                 */
            	
              	if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
                    logger.logDebug(
                               " INVITE transaction not found");
              	// if ( this.isBackToBackUserAgent() ) {
              	// 	this.releaseAckSem();
              	// }
              	return false;
               
            }
        }
    }

    public String getEarlyDialogId() {
        return earlyDialogId;
    }

    // /**
    //  * Release the semaphore for ACK processing so the next re-INVITE may
    //  * proceed.
    //  */
    // void releaseAckSem() {
    // 	// if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
    //     //     logger
    //     //             .logDebug("releaseAckSem-enter]]" + this + " sem=" + this.ackSem + " b2bua=" + this.isBackToBackUserAgent);
    //     //     logger.logStackTrace();
    //     // }
    //     // if (this.isBackToBackUserAgent) {
    //     //     if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
    //     //         logger
    //     //                 .logDebug("releaseAckSem]]" + this + " sem=" + this.ackSem);
    //     //         logger.logStackTrace();
    //     //     }
    //     //     if (this.ackSem.availablePermits() == 0 ) {
    //     //         this.ackSem.release();
    //     //         if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
    //     //             logger
    //     //                     .logDebug("releaseAckSem]]" + this + " sem=" + this.ackSem);
    //     //         }
    //     //     }
    //     // }
    // }

    // boolean isBlockedForReInvite() {
    // 	return this.ackSem.availablePermits() == 0;
    // }
    
    // boolean takeAckSem() {
    //     // if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
    //     //     logger.logDebug("[takeAckSem " + this + " sem=" + this.ackSem);
    //     // }
    //     // try {
        	
    //     //     if (!this.ackSem.tryAcquire(2, TimeUnit.SECONDS)) {
    //     //         if (logger.isLoggingEnabled()) {
    //     //             logger.logError(
    //     //                     "Cannot aquire ACK semaphore ");
    //     //         }

    //     //         if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
    //     //             logger.logDebug(
    //     //                     "Semaphore previously acquired at "
    //     //                             + this.stackTrace + " sem=" + this.ackSem);
    //     //             logger.logStackTrace();

    //     //         }
    //     //         return false;
    //     //     }
   
    //     //     if (logger.isLoggingEnabled(
    //     //             StackLogger.TRACE_DEBUG)) {

    //     //         this.recordStackTrace();
    //     //     }

    //     // } catch (InterruptedException ex) {
    //     //     logger.logError("Cannot aquire ACK semaphore");
    //     //     return false;

    //     // }
    //     return true;

    // }

    /**
     * @param lastAckSent
     *            the lastAckSent to set
     */
    protected void setLastAckSent(SIPRequest lastAckSent) {
        this.lastAckSent = new ACKWrapper(sipStack, lastAckSent);
    }

    /**
     * @return true if an ack was ever sent for this Dialog
     */
    public boolean isAtleastOneAckSent() {
        return this.isAcknowledged;
    }

    public boolean isBackToBackUserAgent() {
        return this.isBackToBackUserAgent;
    }

    public void doDeferredDeleteIfNoAckSent(long seqno) {
    	if (sipStack.getTimer() == null) {
            this.setState(TERMINATED_STATE);
        } else if (dialogDeleteIfNoAckSentTask == null) {
            // Delete the transaction after the max ack timeout.
        	dialogDeleteIfNoAckSentTask = new DialogDeleteIfNoAckSentTask(getCallId().getCallId(), this, seqno);
            if (sipStack.getTimer() != null && sipStack.getTimer().isStarted()) {
            	int delay = SIPTransactionStack.BASE_TIMER_INTERVAL;
            	if(lastTransaction != null) {
            		delay = lastTransaction.getBaseTimerInterval();
            	}
            	sipStack.getTimer().schedule(
                    dialogDeleteIfNoAckSentTask,
                    sipStack.getAckTimeoutFactor()
                            * delay);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nist.javax.sip.DialogExt#setBackToBackUserAgent(boolean)
     */
    public void setBackToBackUserAgent() {
        this.isBackToBackUserAgent = true;
    }

    /**
     * @return the eventHeader
     */
    EventHeader getEventHeader() {
        return eventHeader;
    }

    /**
     * @param eventHeader
     *            the eventHeader to set
     */
    void setEventHeader(Event eventHeader) {
        this.eventHeader = eventHeader;
    }    

    /**
     * @param reInviteFlag
     *            the reinviteFlag to set
     */
    protected void setReInviteFlag(boolean reInviteFlag) {
        this.reInviteFlag = reInviteFlag;
    }

    public boolean isSequenceNumberValidation() {
        return this.sequenceNumberValidation;
    }

    public void disableSequenceNumberValidation() {
        this.sequenceNumberValidation = false;
    }

    // public void acquireTimerTaskSem() {
    //     boolean acquired = false;
    //     try {
    //         acquired = this.timerTaskLock.tryAcquire(10, TimeUnit.SECONDS);
    //     } catch (InterruptedException ex) {
    //         acquired = false;
    //     }
    //     if (!acquired) {
    //         throw new IllegalStateException(
    //                 "Impossible to acquire the dialog timer task lock");
    //     }
    // }

    // public void releaseTimerTaskSem() {
    //     this.timerTaskLock.release();
    // }

    public String getMergeId() {
        return this.firstTransactionMergeId;
    }

    public void setPendingRouteUpdateOn202Response(SIPRequest sipRequest) {
        this.pendingRouteUpdateOn202Response = true;
        // Issue 374 : patch from ivan dubrov : get the from tag instead of to tag
        String fromTag = sipRequest.getFromHeader().getTag();
        if (fromTag != null) {
            this.setRemoteTag(fromTag);
        }

    }

    public String getLastResponseMethod() {
        return lastResponseMethod;
    }

    public Integer getLastResponseStatusCode() {
        return lastResponseStatusCode;
    }

    public long getLastResponseCSeqNumber() {
        return lastResponseCSeqNumber;
    }

    // jeand cleanup the dialog from the data not needed anymore upon receiving
    // or sending an ACK
    // to save on mem
    protected void cleanUpOnAck() {
        if (getReleaseReferencesStrategy() != ReleaseReferencesStrategy.None) {
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger.logDebug(
                        "cleanupOnAck : " + getDialogId());
            }
            if (originalRequest != null) {
                if (originalRequestRecordRouteHeaders != null) {
                    originalRequestRecordRouteHeadersString = originalRequestRecordRouteHeaders
                            .toString();
                }
                originalRequestRecordRouteHeaders = null;
                originalRequest = null;
            }
            if (firstTransaction != null) {
                if (firstTransaction.getOriginalRequest() != null) {
                    firstTransaction.getOriginalRequest().cleanUp();
                }
                firstTransaction = null;
            }
            if (lastTransaction != null) {
                if (lastTransaction.getOriginalRequest() != null) {
                    lastTransaction.getOriginalRequest().cleanUp();
                }
                lastTransaction = null;
            }
            if (callIdHeader != null) {
                callIdHeaderString = callIdHeader.toString();
                callIdHeader = null;
            }
            if (contactHeader != null) {
                contactHeaderStringified = contactHeader.toString();
                contactHeader = null;
            }
            if (remoteTarget != null) {
                remoteTargetStringified = remoteTarget.toString();
                remoteTarget = null;
            }
            if (remoteParty != null) {
                remotePartyStringified = remoteParty.toString();
                remoteParty = null;
            }
            if (localParty != null) {
                localPartyStringified = localParty.toString();
                localParty = null;
            }
            pendingReliableResponseAsBytes = null;
            pendingReliableResponseMethod = null;
        }
    }

    /**
     * Release references so the GC can clean up dialog state.
     * 
     */
    protected void cleanUp() {
        if (getReleaseReferencesStrategy() != ReleaseReferencesStrategy.None) {
            cleanUpOnAck();
            if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
                logger
                        .logDebug("dialog cleanup : " + getDialogId());
            }
            // timerTaskLock = null;
            // ackSem = null;            
            contactHeader = null;
            eventHeader = null;
            firstTransactionId = null;
            firstTransactionMethod = null;            
            // Cannot clear up the last Ack Sent. until DIALOG is terminated.
          
            // lastAckReceivedCSeqNumber = null;
            lastResponseDialogId = null;         
            lastResponseMethod = null;
            lastResponseTopMostVia = null;            
            if (originalRequestRecordRouteHeaders != null) {
                originalRequestRecordRouteHeaders.clear();
                originalRequestRecordRouteHeaders = null;
                originalRequestRecordRouteHeadersString = null;
            }            
            if (routeList != null) {
                routeList.clear();
                routeList = null;
            }
            responsesReceivedInForkingCase.clear();
        }
    }

    protected RecordRouteList getOriginalRequestRecordRouteHeaders() {
        if (originalRequestRecordRouteHeaders == null
                && originalRequestRecordRouteHeadersString != null) {
            try {
                originalRequestRecordRouteHeaders = (RecordRouteList) new RecordRouteParser(
                        originalRequestRecordRouteHeadersString).parse();
            } catch (ParseException e) {
                logger
                        .logError(
                                "error reparsing the originalRequest RecordRoute Headers",
                                e);
            }
            originalRequestRecordRouteHeadersString = null;
        }
        return originalRequestRecordRouteHeaders;
    }

    /**
     * @return the lastResponseTopMostVia
     */
    public Via getLastResponseTopMostVia() {
        return lastResponseTopMostVia;
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nist.javax.sip.DialogExt#isReleaseReferences()
     */
    public ReleaseReferencesStrategy getReleaseReferencesStrategy() {
        return releaseReferencesStrategy;
    }

    /*
     * (non-Javadoc)
     * 
     * @see gov.nist.javax.sip.DialogExt#setReleaseReferences(boolean)
     */
    public void setReleaseReferencesStrategy(ReleaseReferencesStrategy releaseReferencesStrategy) {
        this.releaseReferencesStrategy = releaseReferencesStrategy;
    }

    public void setEarlyDialogTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("Invalid value " + seconds);
        }
        this.earlyDialogTimeout = seconds;
    }

    public void checkRetransmissionForForking(SIPResponse response) {
        final int statusCode = response.getStatusCode();
        final String responseMethod = response.getCSeqHeader().getMethod();
        final long responseCSeqNumber = response.getCSeq().getSeqNumber();
        /*From RFC3262, 1 Introduction: Each provisional response is given a sequence number, carried in the
        RSeq header field in the response.  The PRACK messages contain an
        RAck header field, which indicates the sequence number of the
        provisional response that is being acknowledged.*/

        RSeq rseq = (RSeq) response.getHeader(RSeqHeader.NAME);
        String retransCondition =(statusCode + "/" + responseCSeqNumber + "/" + responseMethod);
        if (statusCode >= 100 && statusCode <200){
            if(response.getContent()!=null){
                final int sdpHachCode = response.getContent().hashCode();
                retransCondition =(statusCode + "/" + responseCSeqNumber + "/" + responseMethod+ "/" + sdpHachCode);
            }
        }
        if(rseq != null) {
            retransCondition = retransCondition + "/" + rseq.getSeqNumber();
        }
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug("retransCondition is : " + retransCondition);
        }
        boolean isRetransmission = !responsesReceivedInForkingCase.add(retransCondition);
        response.setRetransmission(isRetransmission);            
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "marking response as retransmission " + isRetransmission + " for dialog " + this);
        }
    }

   @Override
   public int hashCode() {
	   if ( (callIdHeader == null) &&
			   // https://java.net/jira/browse/JSIP-493
			   (callIdHeaderString == null)) {
           return 0;
       } else {
           return getCallId().getCallId().hashCode();
       }
   }

    /**
     * In case of forking scenarios, set the original dialog that had been forked
     * @param originalDialog
     */
    public void setOriginalDialog(SIPDialog originalDialog) {
      this.originalDialog = originalDialog;
      
    }
    
    @Override
    public boolean isForked() {
      return originalDialog != null;
    }
    
    @Override
    public Dialog getOriginalDialog() {
      return originalDialog;
    }     

    protected void startReliableResponseTimer(SIPServerTransactionImpl serverTransaction) {
        provisionalResponseTask = new ProvisionalResponseTask(this, serverTransaction);
        sipStack.getTimer().schedule(provisionalResponseTask, SIPTransactionStack.BASE_TIMER_INTERVAL);    
    }
    
    protected void restartReliableResponseTimer(SIPServerTransactionImpl serverTransaction, int ticks) {
        sipStack.getTimer().schedule(provisionalResponseTask, ticks * SIPTransactionStack.BASE_TIMER_INTERVAL);    
    }
    
    protected void stopReliableResponseTimer() {
        if (provisionalResponseTask != null) {
            sipStack.getTimer().cancel(provisionalResponseTask);
            this.provisionalResponseTask = null;
        }
    }

    protected void startEarlyStateTimer() {
    	String dialogId = this.dialogId;
    	if(dialogId==null)
    		dialogId = earlyDialogId;
    	
        this.earlyStateTimerTask = new EarlyStateTimerTask(getCallId().getCallId(), this);
        logger.logDebug(
                "EarlyStateTimerTask " + earlyStateTimerTask + " created "
                        + this.earlyDialogTimeout * 1000);
        if (sipStack.getTimer() != null && sipStack.getTimer().isStarted() ) {
            sipStack.getTimer().schedule(this.earlyStateTimerTask,
                this.earlyDialogTimeout * 1000);
        }
    }
    
    protected void stopEarlyStateTimer() {
        if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
            logger.logDebug(
                    "Trying to stop Early Dialog Timer " + this.earlyStateTimerTask);
        }
        if (this.earlyStateTimerTask != null) {
            logger.logDebug(
                "EarlyStateTimerTask " + earlyStateTimerTask + " cancelled");
            sipStack.getTimer()
                    .cancel(this.earlyStateTimerTask);
            this.earlyStateTimerTask = null;
        }
    }
    
    protected void cancelDialogDelete() {
    	if (dialogDeleteTask != null) {
            getStack().getTimer().cancel(dialogDeleteTask);
            dialogDeleteTask = null;
        }
    }
}
