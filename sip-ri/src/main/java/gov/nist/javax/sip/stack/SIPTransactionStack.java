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
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.DialogTerminatedEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.Transaction;
import javax.sip.TransactionState;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Hop;
import javax.sip.address.Router;
import javax.sip.header.CallIdHeader;
import javax.sip.header.EventHeader;
import javax.sip.message.Request;

import gov.nist.core.CommonLogger;
import gov.nist.core.Host;
import gov.nist.core.HostPort;
import gov.nist.core.LogLevels;
import gov.nist.core.LogWriter;
import gov.nist.core.ServerLogger;
import gov.nist.core.StackLogger;
import gov.nist.core.ThreadAuditor;
import gov.nist.core.executor.MessageProcessorExecutor;
import gov.nist.core.executor.StackExecutor;
import gov.nist.core.net.AddressResolver;
import gov.nist.core.net.DefaultNetworkLayer;
import gov.nist.core.net.NetworkLayer;
import gov.nist.core.net.SecurityManagerProvider;
import gov.nist.javax.sip.DefaultAddressResolver;
import gov.nist.javax.sip.ListeningPointImpl;
import gov.nist.javax.sip.LogRecordFactory;
import gov.nist.javax.sip.ReleaseReferencesStrategy;
import gov.nist.javax.sip.SIPConstants;
import gov.nist.javax.sip.SipListenerExt;
import gov.nist.javax.sip.SipProviderImpl;
import gov.nist.javax.sip.SipStackImpl;
import gov.nist.javax.sip.Utils;
import gov.nist.javax.sip.header.Event;
import gov.nist.javax.sip.header.Via;
import gov.nist.javax.sip.header.extensions.JoinHeader;
import gov.nist.javax.sip.header.extensions.ReplacesHeader;
import gov.nist.javax.sip.message.SIPMessage;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.parser.MessageParserFactory;
import gov.nist.javax.sip.stack.timers.SIPStackTimerTask;
import gov.nist.javax.sip.stack.timers.SipTimer;
import gov.nist.javax.sip.stack.transports.processors.ClientAuthType;
import gov.nist.javax.sip.stack.transports.processors.ConnectionOrientedMessageProcessor;
import gov.nist.javax.sip.stack.transports.processors.MessageChannel;
import gov.nist.javax.sip.stack.transports.processors.MessageProcessor;
import gov.nist.javax.sip.stack.transports.processors.MessageProcessorFactory;
import gov.nist.javax.sip.stack.transports.processors.RawMessageChannel;
import gov.nist.javax.sip.stack.transports.processors.netty.IncomingMessageProcessingTask;
import gov.nist.javax.sip.stack.transports.processors.netty.NettyStreamMessageProcessor;
import gov.nist.javax.sip.stack.transports.processors.nio.NIOMode;
import gov.nist.javax.sip.stack.transports.processors.nio.NioTcpMessageProcessor;
import gov.nist.javax.sip.stack.transports.processors.nio.NioTlsMessageProcessor;
import gov.nist.javax.sip.stack.transports.processors.oio.TCPMessageProcessor;
import gov.nist.javax.sip.stack.transports.processors.oio.TLSMessageProcessor;

/*
 * Jeff Keyser : architectural suggestions and contributions. Pierre De Rop and Thomas Froment :
 * Bug reports. Jeyashankher < jai@lucent.com > : bug reports. Jeroen van Bemmel : Bug fixes.
 *
 *
 */

/**
 *
 * This is the sip stack. It is essentially a management interface. It manages
 * the resources for the JAIN-SIP implementation. This is the structure that is
 * wrapped by the SipStackImpl.
 *
 * @see gov.nist.javax.sip.SipStackImpl
 *
 * @author M. Ranganathan <br/>
 *
 * @version 1.2 $Revision: 1.180 $ $Date: 2010-12-02 22:04:15 $
 */
public abstract class SIPTransactionStack implements SIPTransactionEventListener {
	private static StackLogger logger = CommonLogger.getLogger(SIPTransactionStack.class);
	/*
	 * Number of milliseconds between timer ticks (500).
	 */
	public static final int BASE_TIMER_INTERVAL = 500;

	protected static final Integer MAX_WORKERS = 4;

	/*
	 * Connection linger time (seconds) this is the time (in seconds) for which we
	 * linger the TCP connection before closing it.
	 */
	// Moved to non constant as part of
	// https://github.com/Mobicents/jain-sip/issues/40
	private static int connectionLingerTimer = 8;

	/*
	 * Dialog Early state timeout duration.
	 */
	protected int earlyDialogTimeout = 180;

	/*
	 * Table of retransmission Alert timers.
	 */
	protected ConcurrentHashMap<String, SIPServerTransaction> retransmissionAlertTransactions;

	// Table of early dialogs ( to keep identity mapping )
	protected ConcurrentHashMap<String, SIPDialog> earlyDialogTable;

	// Table of dialogs.
	protected ConcurrentHashMap<String, SIPDialog> dialogTable;

	// Table of server dialogs ( for loop detection)
	protected ConcurrentHashMap<String, SIPDialog> serverDialogMergeTestTable;

	// A set of methods that result in dialog creations.
	protected static final Set<String> dialogCreatingMethods = new HashSet<String>();

	// Global timer. Use this for all timer tasks.
	protected SipTimer timer;
	// Global Message Processor Executor. Use this for all tasks except timers.
	protected StackExecutor messageProcessorExecutor = null;

	// List of pending server transactions
	private ConcurrentHashMap<String, SIPServerTransaction> pendingTransactions;

	// hashtable for fast lookup
	protected ConcurrentHashMap<String, SIPClientTransaction> clientTransactionTable;

	// Set to false if you want hiwat and lowat to be consulted.
	protected boolean unlimitedServerTransactionTableSize = true;

	// Set to false if you want unlimited size of client trnansactin table.
	protected boolean unlimitedClientTransactionTableSize = true;

	// High water mark for ServerTransaction Table
	// after which requests are dropped.
	protected int serverTransactionTableHighwaterMark = 5000;

	// Low water mark for Server Tx table size after which
	// requests are selectively dropped
	protected int serverTransactionTableLowaterMark = 4000;

	// Hiwater mark for client transaction table. These defaults can be
	// overriden by stack
	// configuration.
	protected int clientTransactionTableHiwaterMark = 1000;

	// Low water mark for client tx table.
	protected int clientTransactionTableLowaterMark = 800;

	private AtomicInteger activeClientTransactionCount = new AtomicInteger(0);

	// Hashtable for server transactions.
	protected ConcurrentHashMap<String, SIPServerTransaction> serverTransactionTable;

	// A table of ongoing transactions indexed by mergeId ( for detecting merged
	// requests.
	private ConcurrentHashMap<String, SIPServerTransaction> mergeTable;

	private ConcurrentHashMap<String, SIPServerTransaction> terminatedServerTransactionsPendingAck;

	// private ConcurrentHashMap<String,SIPClientTransaction>
	// forkedClientTransactionTable;

	protected boolean deliverRetransmittedAckToListener = false;

	/*
	 * ServerLog is used just for logging stack message tracecs.
	 */
	protected ServerLogger serverLogger;

	/*
	 * We support UDP on this stack.
	 */
	protected boolean udpFlag;

	/*
	 * Internal router. Use this for all sip: request routing.
	 */
	protected DefaultRouter defaultRouter;

	/*
	 * Global flag that turns logging off
	 */
	protected boolean needsLogging;

	/*
	 * Flag used for testing TI, bypasses filtering of ACK to non-2xx
	 */
	private boolean non2XXAckPassedToListener;

	/*
	 * Flag that indicates that the stack is active.
	 */
	protected boolean toExit;

	/*
	 * Name of the stack.
	 */
	protected String stackName;

	/*
	 * IP address of stack -- this can be re-written by stun.
	 *
	 * @deprecated
	 */
	protected String stackAddress;

	/*
	 * INET address of stack (cached to avoid repeated lookup)
	 *
	 * @deprecated
	 */
	protected InetAddress stackInetAddress;

	/*
	 * Request factory interface (to be provided by the application)
	 */
	protected StackMessageFactory sipMessageFactory;

	/*
	 * Router to determine where to forward the request.
	 */
	protected javax.sip.address.Router router;

	/*
	 * Number of pre-allocated threads for processing udp messages. -1 means no
	 * preallocated threads ( dynamically allocated threads).
	 */
	protected int threadPoolSize;

	/*
	 * Time between checks for executing tasks, defaulting to 10 ms.
	 */
	protected long taskInterval = 10L;

	/*
	 * max number of simultaneous connections.
	 */
	protected int maxConnections;

	/*
	 * Close accept socket on completion.
	 */
	protected boolean cacheServerConnections;

	/*
	 * Close connect socket on Tx termination.
	 */
	protected boolean cacheClientConnections;

	/*
	 * Use the user supplied router for all out of dialog requests.
	 */
	protected boolean useRouterForAll;

	/*
	 * Max size of message that can be read from a TCP connection.
	 */
	protected int maxContentLength;

	/*
	 * Max # of headers that a SIP message can contain.
	 */
	protected int maxMessageSize;

	/*
	 * Max message size that can be sent over UDP.
	 */
	protected int maxUdpMessageSize;

	/*
	 * A collection of message processors.
	 */
	private ConcurrentHashMap<String, MessageProcessor> messageProcessors;

	/*
	 * Read timeout on TCP incoming sockets -- defines the time between reads for
	 * after delivery of first byte of message.
	 */
	protected int readTimeout;

	/*
	 * Conn timeout for TCP outgoign sockets -- maximum time in millis the stack
	 * will wait to open a connection.
	 */
	protected int connTimeout = 10000;

	/*
	 * The socket factory. Can be overriden by applications that want direct access
	 * to the underlying socket.
	 */
	protected NetworkLayer networkLayer;

	/*
	 * Outbound proxy String ( to be handed to the outbound proxy class on
	 * creation).
	 */
	protected String outboundProxy;

	protected String routerPath;

	// Flag to indicate whether the stack will provide dialog
	// support.
	protected boolean isAutomaticDialogSupportEnabled;

	// The set of events for which subscriptions can be forked.

	protected HashSet<String> forkedEvents;

	// Generate a timestamp header for retransmitted requests.
	protected boolean generateTimeStampHeader;

	protected AddressResolver addressResolver;

	// Max time that the listener is allowed to take to respond to a
	// request. Default is "infinity". This property allows
	// containers to defend against buggy clients (that do not
	// want to respond to requests).
	protected int maxListenerResponseTime;

	// http://java.net/jira/browse/JSIP-420
	// Max time that an INVITE tx is allowed to live in the stack. Default is
	// infinity
	protected int maxTxLifetimeInvite;
	// Max time that a Non INVITE tx is allowed to live in the stack. Default is
	// infinity
	protected int maxTxLifetimeNonInvite;

	// / Provides a mechanism for applications to check the health of threads in
	// the stack
	protected ThreadAuditor threadAuditor = null;

	protected LogRecordFactory logRecordFactory;

	// Set to true if the client CANCEL transaction should be checked before
	// sending
	// it out.
	protected boolean cancelClientTransactionChecked = true;

	// Is to tag reassignment allowed.
	protected boolean remoteTagReassignmentAllowed = true;

	protected boolean logStackTraceOnMessageSend = true;

	// Receive UDP buffer size
	protected int receiveUdpBufferSize;

	// Send UDP buffer size
	protected int sendUdpBufferSize;

	// Receive TCP buffer size
	protected int tcpSoRcvbuf;

	// Send TCP buffer size
	protected int tcpSoSndbuf;

	private int stackCongestionControlTimeout = 0;

	protected boolean isBackToBackUserAgent = false;

	protected boolean checkBranchId;

	protected boolean isAutomaticDialogErrorHandlingEnabled = true;

	protected boolean isDialogTerminatedEventDeliveredForNullDialog = false;

	// Max time for a forked response to arrive. After this time, the original
	// dialog
	// is not tracked. If you want to track the original transaction you need to
	// specify
	// the max fork time with a stack init property.
	protected int maxForkTime = 0;

	// Whether or not to deliver unsolicited NOTIFY

	private boolean deliverUnsolicitedNotify = false;

	private boolean deliverTerminatedEventForAck = false;

	protected boolean patchWebSocketHeaders = false;

	protected boolean patchRport = false;

	protected boolean patchReceivedRport = false;

	protected ClientAuthType clientAuth = ClientAuthType.Default;

	// Minimum time between NAT kee alive pings from clients.
	// Any ping that exceeds this time will result in CRLF CRLF going
	// from the UDP message channel.
	protected long minKeepAliveInterval = -1L;

	// The time after which a "dialog timeout event" is delivered to a listener.
	protected int dialogTimeoutFactor = 64;

	// factory used to create MessageParser objects
	public MessageParserFactory messageParserFactory;
	// factory used to create MessageProcessor objects
	public MessageProcessorFactory messageProcessorFactory;

	public long nioSocketMaxIdleTime;

	public NIOMode nioMode = NIOMode.BLOCKING;

	private ReleaseReferencesStrategy releaseReferencesStrategy = ReleaseReferencesStrategy.None;

	public List<SIPMessageValve> sipMessageValves;

	public SIPEventInterceptor sipEventInterceptor;

	private int threadPriority = Thread.MAX_PRIORITY;

	/*
	 * The socket factory. Can be overriden by applications that want direct access
	 * to the underlying socket.
	 */

	protected SecurityManagerProvider securityManagerProvider;

	/**
	 * Keepalive support and cleanup for client-initiated connections as per RFC
	 * 5626.
	 *
	 * Based on the maximum CRLF keep-alive period of 840 seconds, per
	 * http://tools.ietf.org/html/rfc5626#section-4.4.1. a value < 0 means that the
	 * RFC 5626 will not be triggered, as a default we don't enable it not to change
	 * existing apps behavior.
	 */
	protected int reliableConnectionKeepAliveTimeout = -1;

	private long sslHandshakeTimeout = -1;

	private boolean allowDialogOnDifferentProvider = false;

	private boolean sslRenegotiationEnabled = false;

	// SctpStandardSocketOptions

	// SCTP option: Enables or disables message fragmentation.
	// If enabled no SCTP message fragmentation will be performed.
	// Instead if a message being sent exceeds the current PMTU size,
	// the message will NOT be sent and an error will be indicated to the user.
	protected Boolean sctpDisableFragments = null;
	// SCTP option: Fragmented interleave controls how the presentation of messages
	// occur for the message receiver.
	// There are three levels of fragment interleave defined
	// level 0 - Prevents the interleaving of any messages
	// level 1 - Allows interleaving of messages that are from different
	// associations
	// level 2 - Allows complete interleaving of messages.
	protected Integer sctpFragmentInterleave = null;
	// SCTP option: The maximum number of streams requested by the local endpoint
	// during association initialization
	// For an SctpServerChannel this option determines the maximum number of
	// inbound/outbound streams
	// accepted sockets will negotiate with their connecting peer.
	// protected Integer sctpInitMaxStreams = null;
	// SCTP option: Enables or disables a Nagle-like algorithm.
	// The value of this socket option is a Boolean that represents whether the
	// option is enabled or disabled.
	// SCTP uses an algorithm like The Nagle Algorithm to coalesce short segments
	// and improve network efficiency.
	protected Boolean sctpNoDelay = true;
	// SCTP option: The size of the socket send buffer.
	protected Integer sctpSoSndbuf = null;
	// SCTP option: The size of the socket receive buffer.
	protected Integer sctpSoRcvbuf = null;
	// SCTP option: Linger on close if data is present.
	// The value of this socket option is an Integer that controls the action taken
	// when unsent data is queued on the socket
	// and a method to close the socket is invoked.
	// If the value of the socket option is zero or greater, then it represents a
	// timeout value, in seconds, known as the linger interval.
	// The linger interval is the timeout for the close method to block while the
	// operating system attempts to transmit the unsent data
	// or it decides that it is unable to transmit the data.
	// If the value of the socket option is less than zero then the option is
	// disabled.
	// In that case the close method does not wait until unsent data is transmitted;
	// if possible the operating system will transmit any unsent data before the
	// connection is closed.
	protected Integer sctpSoLinger = null;

	protected boolean computeContentLengthFromMessage = false;

	public StackExecutor getMessageProcessorExecutor() {
		return messageProcessorExecutor;
	}

	public void setMessageProcessorExecutor(MessageProcessorExecutor messageProcessorExecutor) {
		this.messageProcessorExecutor = messageProcessorExecutor;
	}

	// / Timer to regularly ping the thread auditor (on behalf of the timer
	// thread)
	protected class PingTimer extends SIPStackTimerTask {
		// / Timer thread handle
		ThreadAuditor.ThreadHandle threadHandle;

		// / Constructor
		public PingTimer(ThreadAuditor.ThreadHandle a_oThreadHandle) {
			super(PingTimer.class.getSimpleName());
			threadHandle = a_oThreadHandle;
		}

		@Override
		public String getId() {
			return threadHandle.toString();
		}

		public void runTask() {
			// Check if we still have a timer (it may be null after shutdown)
			if (getTimer() != null) {
				// Register the timer task if we haven't done so
				// Contribution for https://github.com/Mobicents/jain-sip/issues/39
				if (threadHandle == null && getThreadAuditor() != null) {
					// This happens only once since the thread handle is passed
					// to the next scheduled ping timer
					threadHandle = getThreadAuditor().addCurrentThread();
				}

				// Let the thread auditor know that the timer task is alive
				threadHandle.ping();

				// Schedule the next ping
				getTimer().schedule(new PingTimer(threadHandle), threadHandle.getPingIntervalInMillisecs());
			}
		}
	}

	class RemoveForkedTransactionTimerTask extends SIPStackTimerTask {
		String id;
		String forkId;
		String transactionId;
		SipProvider sipProvider;

		public RemoveForkedTransactionTimerTask(String id, String transactionId, String forkId,
				SipProvider sipProvider) {
			super(RemoveForkedTransactionTimerTask.class.getSimpleName());
			this.id = id;
			this.forkId = forkId;
			this.transactionId = transactionId;
			this.sipProvider = sipProvider;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public void runTask() {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("Removing forked client transaction : forkId = " + forkId);
			}

			SIPTransaction removed = removeTransactionById(transactionId, false);
			sendTransactionTerminatedEvent(removed, sipProvider);
		}
	}

	static {
		// Standard set of methods that create dialogs.
		dialogCreatingMethods.add(Request.REFER);
		dialogCreatingMethods.add(Request.INVITE);
		dialogCreatingMethods.add(Request.SUBSCRIBE);
	}

	/**
	 * Default constructor.
	 */
	protected SIPTransactionStack() {
		this.toExit = false;
		this.forkedEvents = new HashSet<String>();
		// set of events for which subscriptions can be forked.
		// Set an infinite thread pool size.
		this.threadPoolSize = -1;
		// Close response socket after infinte time.
		// for max performance
		this.cacheServerConnections = true;
		// Close the request socket after infinite time.
		// for max performance
		this.cacheClientConnections = true;
		// Max number of simultaneous connections.
		this.maxConnections = -1;
		// Array of message processors.
		// jeand : using concurrent data structure to avoid excessive blocking
		messageProcessors = new ConcurrentHashMap<String, MessageProcessor>();

		// The read time out is infinite.
		this.readTimeout = -1;

		this.maxListenerResponseTime = -1;

		// The default (identity) address lookup scheme

		this.addressResolver = new DefaultAddressResolver();

		// Init vavles list
		this.sipMessageValves = new ArrayList<SIPMessageValve>();

		// Notify may or may not create a dialog. This is handled in
		// the code.
		// Create the transaction collections

		// Dialog dable.
		this.dialogTable = new ConcurrentHashMap<String, SIPDialog>();
		this.earlyDialogTable = new ConcurrentHashMap<String, SIPDialog>();
		this.serverDialogMergeTestTable = new ConcurrentHashMap<String, SIPDialog>();

		clientTransactionTable = new ConcurrentHashMap<String, SIPClientTransaction>();
		serverTransactionTable = new ConcurrentHashMap<String, SIPServerTransaction>();
		this.terminatedServerTransactionsPendingAck = new ConcurrentHashMap<String, SIPServerTransaction>();
		mergeTable = new ConcurrentHashMap<String, SIPServerTransaction>();
		retransmissionAlertTransactions = new ConcurrentHashMap<String, SIPServerTransaction>();

		// Start the timer event thread.

//        this.timer = new DefaultTimer();
		this.pendingTransactions = new ConcurrentHashMap<String, SIPServerTransaction>();

		// this.forkedClientTransactionTable = new
		// ConcurrentHashMap<String,SIPClientTransaction>();
	}

	/**
	 * Re Initialize the stack instance.
	 */
	protected void reInit() {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
			logger.logDebug("Re-initializing !");

		// Array of message processors.
		messageProcessors = new ConcurrentHashMap<String, MessageProcessor>();
		// Handle IO for this process.
		pendingTransactions = new ConcurrentHashMap<String, SIPServerTransaction>();
		clientTransactionTable = new ConcurrentHashMap<String, SIPClientTransaction>();
		serverTransactionTable = new ConcurrentHashMap<String, SIPServerTransaction>();
		retransmissionAlertTransactions = new ConcurrentHashMap<String, SIPServerTransaction>();
		mergeTable = new ConcurrentHashMap<String, SIPServerTransaction>();
		// Dialog dable.
		this.dialogTable = new ConcurrentHashMap<String, SIPDialog>();
		this.earlyDialogTable = new ConcurrentHashMap<String, SIPDialog>();
		this.serverDialogMergeTestTable = new ConcurrentHashMap<String, SIPDialog>();
		this.terminatedServerTransactionsPendingAck = new ConcurrentHashMap<String, SIPServerTransaction>();
		// this.forkedClientTransactionTable = new
		// ConcurrentHashMap<String,SIPClientTransaction>();

		this.activeClientTransactionCount = new AtomicInteger(0);

	}

	/**
	 * Creates and binds, if necessary, a socket connected to the specified
	 * destination address and port and then returns its local address.
	 *
	 * @param dst          the destination address that the socket would need to
	 *                     connect to.
	 * @param dstPort      the port number that the connection would be established
	 *                     with.
	 * @param localAddress the address that we would like to bind on (null for the
	 *                     "any" address).
	 * @param localPort    the port that we'd like our socket to bind to (0 for a
	 *                     random port).
	 *
	 * @return the SocketAddress that this handler would use when connecting to the
	 *         specified destination address and port.
	 *
	 * @throws IOException if binding the socket fails
	 */
	// public SocketAddress getLocalAddressForTcpDst(InetAddress dst, int dstPort,
	// InetAddress localAddress, int localPort) throws IOException {
	// if (getMessageProcessorFactory() instanceof NioMessageProcessorFactory) {
	// // First find the TLS message processor
	// MessageProcessor[] processors = getMessageProcessors();
	// for (MessageProcessor processor : processors){
	// if ("TCP".equals(processor.getTransport())) {
	// NioTcpMessageChannel msgChannel =
	// (NioTcpMessageChannel) processor.createMessageChannel(dst, dstPort);
	// return msgChannel.socketChannel.socket().getLocalSocketAddress();
	// }
	// }
	// return null;
	// }
	// return this.ioHandler.getLocalAddressForTcpDst(
	// dst, dstPort, localAddress, localPort);
	// }

	/**
	 * Creates and binds, if necessary, a TCP SSL socket connected to the specified
	 * destination address and port and then returns its local address.
	 *
	 * @param dst          the destination address that the socket would need to
	 *                     connect to.
	 * @param dstPort      the port number that the connection would be established
	 *                     with.
	 * @param localAddress the address that we would like to bind on (null for the
	 *                     "any" address).
	 *
	 * @return the SocketAddress that this handler would use when connecting to the
	 *         specified destination address and port.
	 *
	 * @throws IOException if binding the socket fails
	 */
	// public SocketAddress getLocalAddressForTlsDst( InetAddress dst,
	// int dstPort, InetAddress localAddress) throws IOException {

	// // First find the TLS message processor
	// MessageProcessor[] processors = getMessageProcessors();
	// for (MessageProcessor processor : processors){
	// if(processor instanceof TLSMessageProcessor){
	// // Here we don't create the channel but if the channel is already
	// // existing will be returned
	// TLSMessageChannel msgChannel =
	// (TLSMessageChannel) processor.createMessageChannel(dst, dstPort);

	// return this.ioHandler.getLocalAddressForTlsDst(
	// dst, dstPort, localAddress, msgChannel);
	// } else if(processor instanceof NioTlsMessageProcessor) {
	// NioTlsMessageChannel msgChannel =
	// (NioTlsMessageChannel) processor.createMessageChannel(dst, dstPort);
	// return msgChannel.socketChannel.socket().getLocalSocketAddress();
	// }
	// }

	// return null;

	// }

	/**
	 * For debugging -- allows you to disable logging or enable logging selectively.
	 *
	 *
	 */
	public void disableLogging() {
		logger.disableLogging();
	}

	/**
	 * Globally enable message logging ( for debugging)
	 *
	 */
	public void enableLogging() {
		logger.enableLogging();
	}

	/**
	 * Print the dialog table.
	 *
	 */
	public void printDialogTable() {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("dialog table  = " + this.dialogTable);
		}
	}

	/**
	 * Retrieve a transaction from our table of transactions with pending
	 * retransmission alerts.
	 *
	 * @param dialogId
	 * @return -- the RetransmissionAlert enabled transaction corresponding to the
	 *         given dialog ID.
	 */
	public SIPServerTransaction getRetransmissionAlertTransaction(String dialogId) {
		return (SIPServerTransaction) this.retransmissionAlertTransactions.get(dialogId);
	}

	/**
	 * Return true if extension is supported.
	 *
	 * @return true if extension is supported and false otherwise.
	 */
	public static boolean isDialogCreatingMethod(String method) {
		return dialogCreatingMethods.contains(method);
	}

	/**
	 * Add an extension method.
	 *
	 * @param extensionMethod -- extension method to support for dialog creation
	 */
	public void addExtensionMethod(String extensionMethod) {
		if (extensionMethod.equals(Request.NOTIFY)) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("NOTIFY Supported Natively");
		} else {
			dialogCreatingMethods.add(Utils.toUpperCase(extensionMethod.trim()));
		}
	}

	/**
	 * Put a dialog into the dialog table.
	 *
	 * @param dialog -- dialog to put into the dialog table.
	 *
	 */
	public SIPDialog putDialog(SIPDialog dialog) {
		String dialogId = dialog.getDialogId();
		SIPDialog existingDialog = getDialog(dialogId);
		if (existingDialog != null) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("putDialog: dialog already exists" + dialogId + " in table = " + existingDialog);
			}
			return existingDialog;
		}
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("putDialog dialogId=" + dialogId + " dialog = " + dialog);
		}
		dialog.setStack(this);
		if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG))
			logger.logStackTrace();
		storeDialog(dialogId, dialog);
		putMergeDialog(dialog);

		return dialog;
	}

	protected void storeDialog(String dialogId, SIPDialog dialog) {
		dialogTable.put(dialogId, dialog);
	}

	/**
	 * Create a dialog and add this transaction to it.
	 *
	 * @param transaction -- tx to add to the dialog.
	 * @return the newly created Dialog.
	 */
	/**
	 * Create a dialog and add this transaction to it.
	 *
	 * @param transaction -- tx to add to the dialog.
	 * @return the newly created Dialog.
	 */
	public SIPDialog createDialog(SIPTransaction transaction) {

		SIPDialog retval = null;

		if (transaction instanceof SIPClientTransaction) {
			String dialogId = ((SIPRequest) transaction.getRequest()).getDialogId(false);
			if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
				logger.logDebug("createDialog dialogId=" + dialogId);
			}
			SIPDialog dialog = getEarlyDialog(dialogId);
			if (dialog != null) {
				if (dialog.getState() == null || dialog.getState() == DialogState.EARLY) {
					retval = dialog;
					if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
						logger.logDebug("createDialog early Dialog found : earlyDialogId=" + dialogId + " earlyDialog= "
								+ dialog);
					}
				} else {
					retval = createNewDialog(transaction, dialogId, true);

				}
			} else {
				retval = createNewDialog(transaction, dialogId, true);
				if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
					logger.logDebug("createDialog early Dialog not found : earlyDialogId=" + dialogId + " created one "
							+ retval);
				}
			}
		} else {
			retval = createNewDialog(transaction, null, false);
		}

		return retval;

	}

	/**
	 * Create a Dialog given a client tx and response.
	 *
	 * @param transaction
	 * @param sipResponse
	 * @return
	 */

	public SIPDialog createDialog(SIPClientTransaction transaction, SIPResponse sipResponse) {
		String originalDialogId = ((SIPRequest) transaction.getRequest()).getDialogId(false);
		String earlyDialogId = sipResponse.getDialogId(false);
		if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
			logger.logDebug("createDialog originalDialogId=" + originalDialogId);
			logger.logDebug("createDialog earlyDialogId=" + earlyDialogId);
			logger.logDebug("createDialog default Dialog=" + transaction.getDefaultDialog());
			if (transaction.getDefaultDialog() != null) {
				logger.logDebug("createDialog default Dialog Id=" + transaction.getDefaultDialog().getDialogId());
			}
		}
		SIPDialog retval = null;
		SIPDialog earlyDialog = getEarlyDialog(originalDialogId);
		if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
			logger.logDebug("createDialog : originalDialogId=" + originalDialogId + " earlyDialog= " + earlyDialog);
			if (earlyDialog == null) {
				logger.logDebug("createDialog : earlyDialogTable=" + earlyDialogTable);
			} else {
				String defaultDialogId = null;
				if (transaction.getDefaultDialog() != null)
					defaultDialogId = transaction.getDefaultDialog().getDialogId();
				logger.logDebug("createDialog : transaction=" + transaction + " transaction default dialg = "
						+ transaction.getDefaultDialog() + " transaction default dialg id = " + defaultDialogId);
			}
		}
		if (earlyDialog != null && transaction != null && (transaction.getDefaultDialog() == null
				|| transaction.getDefaultDialog().getDialogId().equals(originalDialogId))) {
			retval = earlyDialog;
			if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
				logger.logDebug("createDialog early Dialog found : earlyDialogId=" + originalDialogId + " earlyDialog= "
						+ retval);
			}
			if (sipResponse.isFinalResponse()) {
				removeEarlyDialog(originalDialogId);
			}

		} else {
			retval = createNewDialog(transaction, sipResponse);
			if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
				logger.logDebug("createDialog early Dialog not found : earlyDialogId=" + earlyDialogId + " created one "
						+ retval);
			}
		}
		return retval;

	}

	/**
	 * Create a dialog given a transaction.
	 *
	 * @param transaction -- tx to add to the dialog.
	 * @return the newly created Dialog.
	 */
	public SIPDialog createNewDialog(SIPTransaction transaction, String dialogId, boolean isEarlyDialog) {
		SIPDialog sipDialog = new SIPDialog(transaction);
		if (isEarlyDialog) {
			this.earlyDialogTable.put(dialogId, sipDialog);
		}
		return sipDialog;
	}

	/**
	 * Create a dialog given a transaction and a response.
	 *
	 * @param transaction -- tx to add to the dialog.
	 * @param sipResponse -- sipResponse
	 * 
	 * @return the newly created Dialog.
	 */
	public SIPDialog createNewDialog(SIPClientTransaction transaction, SIPResponse sipResponse) {
		return new SIPDialog(transaction, sipResponse);
	}

	/**
	 * Create a Dialog given a sip provider and response.
	 *
	 * @param sipProvider
	 * @param sipResponse
	 * @return
	 */
	public SIPDialog createNewDialog(SipProviderImpl sipProvider, SIPResponse sipResponse) {
		return new SIPDialog(sipProvider, sipResponse);
	}

	/**
	 * Creates a new dialog based on a received NOTIFY. The dialog state is
	 * initialized appropriately. The NOTIFY differs in the From tag
	 * 
	 * Made this a separate method to clearly distinguish what's happening here -
	 * this is a non-trivial case
	 * 
	 * @param subscribeTx - the transaction started with the SUBSCRIBE that we sent
	 * @param notifyST    - the ServerTransaction created for an incoming NOTIFY
	 * @return -- a new dialog created from the subscribe original SUBSCRIBE
	 *         transaction.
	 * 
	 * 
	 */
	public SIPDialog createNewDialog(SIPClientTransaction subscribeTx, SIPTransaction notifyST) {
		return new SIPDialog(subscribeTx, notifyST);
	}

	/**
	 * Remove the dialog from the dialog table.
	 *
	 * @param dialog -- dialog to remove.
	 */
	public void removeDialog(SIPDialog dialog) {
		removeDialog(dialog, dialog.getSipProvider());
	}

	protected void removeDialog(SIPDialog dialog, SipProviderImpl provider) {
		String id = dialog.getDialogId();

		String earlyId = dialog.getEarlyDialogId();

		if (earlyId != null) {
			removeEarlyDialog(earlyId);
			removeDialog(earlyId);
		}

		removeMergeDialog(dialog.getMergeId());

		if (id != null) {

			// FHT: Remove dialog from table only if its associated dialog is
			// the same as the one
			// specified

			//WHY THE HELL WE SHOULD COMPARE MEMORY ADDRESSES?? It can be put only if no dialog found...
			//Object old = getDialog(id);
			//if (old == dialog) {
				removeDialog(id);
			//}
			
			// We now deliver DTE even when the dialog is not originally present
			// in the Dialog
			// Table
			// This happens before the dialog state is assigned.
			boolean isDialogTerminatedEventDelivered = dialog.testAndSetIsDialogTerminatedEventDelivered();
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug(
						"removed dialog from table " + dialog + ", dialog.testAndSetIsDialogTerminatedEventDelivered() "
								+ isDialogTerminatedEventDelivered + ", isDialogTerminatedEventDeliveredForNullDialog"
								+ isDialogTerminatedEventDeliveredForNullDialog);
			}
			if (!isDialogTerminatedEventDelivered) {
				DialogTerminatedEvent event = new DialogTerminatedEvent(provider, dialog);

				// Provide notification to the listener that the dialog has
				// ended.
				provider.handleEvent(event, null);

			}

		} else if (this.isDialogTerminatedEventDeliveredForNullDialog) {
			if (!dialog.testAndSetIsDialogTerminatedEventDelivered()) {
				DialogTerminatedEvent event = new DialogTerminatedEvent(provider, dialog);

				// Provide notification to the listener that the dialog has
				// ended.
				provider.handleEvent(event, null);

			}
		}

	}

	public void removeEarlyDialog(String earlyDialogId) {
		SIPDialog sipDialog = this.earlyDialogTable.remove(earlyDialogId);
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("removeEarlyDialog(" + earlyDialogId + ") : returned " + sipDialog);
		}
	}

	public SIPDialog getEarlyDialog(String dialogId) {

		SIPDialog sipDialog = (SIPDialog) earlyDialogTable.get(dialogId);
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("getEarlyDialog(" + dialogId + ") : returning " + sipDialog);
		}
		return sipDialog;

	}

	protected void removeMergeDialog(String mergeId) {
		if (mergeId != null) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("Tyring to remove Dialog from serverDialogMerge table with Merge Dialog Id " + mergeId);
			}
			SIPDialog sipDialog = serverDialogMergeTestTable.remove(mergeId);
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG) && sipDialog != null) {
				logger.logDebug("removed Dialog " + sipDialog + " from serverDialogMerge table with Merge Dialog Id "
						+ mergeId);
			}
		}
	}

	protected void putMergeDialog(SIPDialog sipDialog) {
		if (sipDialog != null) {
			String mergeId = sipDialog.getMergeId();
			if (mergeId != null) {
				serverDialogMergeTestTable.put(mergeId, sipDialog);
				if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
					logger.logDebug(
							"put Dialog " + sipDialog + " in serverDialogMerge table with Merge Dialog Id " + mergeId);
				}
			}
		}
	}

	/**
	 * Return the dialog for a given dialog ID. If compatibility is enabled then we
	 * do not assume the presence of tags and hence need to add a flag to indicate
	 * whether this is a server or client transaction.
	 *
	 * @param dialogId is the dialog id to check.
	 */

	public SIPDialog getDialog(String dialogId) {

		SIPDialog sipDialog = (SIPDialog) dialogTable.get(dialogId);
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("getDialog(" + dialogId + ") : returning " + sipDialog);
		}
		return sipDialog;

	}

	/**
	 * Remove the dialog given its dialog id. This is used for dialog id
	 * re-assignment only.
	 *
	 * @param dialogId is the dialog Id to remove.
	 */
	public void removeDialog(String dialogId) {
		if (logger.isLoggingEnabled()) {
			logger.logWarning("Silently removing dialog from table");
		}
		dialogTable.remove(dialogId);
	}

	/**
	 * Find a matching client SUBSCRIBE to the incoming notify. NOTIFY requests are
	 * matched to such SUBSCRIBE requests if they contain the same "Call-ID", a "To"
	 * header "tag" parameter which matches the "From" header "tag" parameter of the
	 * SUBSCRIBE, and the same "Event" header field. Rules for comparisons of the
	 * "Event" headers are described in section 7.2.1. If a matching NOTIFY request
	 * contains a "Subscription-State" of "active" or "pending", it creates a new
	 * subscription and a new dialog (unless they have already been created by a
	 * matching response, as described above).
	 * 
	 * Due to different app chaining scenarios (ie B2BUA to Proxy), the RFC matching
	 * is not enough alone. It maybe several transactions matches the defined
	 * criteria. For that reason, some complementary conditions are included. These
	 * conditions will be used as a way to prioritize two matched transactions. In
	 * case, just one transaction matches the RFC criteria, these additional
	 * conditions will be ignored, and the regular logic will be used. Additional
	 * conditions are to match notMsg.reqURI with ct.origReq.contact, and prefer
	 * transactions with dialogs. See https://github.com/RestComm/jain-sip/issues/60
	 * for more info. Complementary are also used to stop the searching, and return
	 * the matched tx. This is because we are iterating the whole client transaction
	 * table, which may be big effort.
	 *
	 * @param notifyMessage
	 * @return -- the matching ClientTransaction with semaphore aquired or null if
	 *         no such client transaction can be found.
	 */
	public SIPClientTransaction findSubscribeTransaction(SIPRequest notifyMessage, ListeningPointImpl listeningPoint) {
		SIPClientTransaction retval = null;
		try {
			// https://github.com/RestComm/jain-sip/issues/60
			// take into account dialogId, so we can try and match the proper TX
			String dialogId = notifyMessage.getDialogId(true);
			Iterator<SIPClientTransaction> it = getAllClientTransactions().iterator();
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("ct table size = " + getClientTransactionTableSize());
			}

			String thisToTag = notifyMessage.getTo().getTag();
			if (thisToTag == null) {
				return retval;
			}
			Event eventHdr = (Event) notifyMessage.getHeader(EventHeader.NAME);
			if (eventHdr == null) {
				if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
					logger.logDebug("event Header is null -- returning null");
				}

				return retval;
			}
			while (it.hasNext()) {
				SIPClientTransaction ct = (SIPClientTransaction) it.next();
				if (!ct.getMethod().equals(Request.SUBSCRIBE))
					continue;

				// if ( sipProvider.getListeningPoint(transport) == null)
				String fromTag = ct.getOriginalRequestFromTag();
				Event hisEvent = (Event) ct.getOriginalRequestEvent();
				// Event header is mandatory but some slopply clients
				// dont include it.
				if (hisEvent == null)
					continue;
				if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
					logger.logDebug("ct.fromTag = " + fromTag);
					logger.logDebug("thisToTag = " + thisToTag);
					logger.logDebug("hisEvent = " + hisEvent);
					logger.logDebug("eventHdr " + eventHdr);
					logger.logDebug("ct.req.contact = " + ct.getOriginalRequestContact());
					if (ct.getOriginalRequest() != null)
						logger.logDebug("ct.req.reqURI = " + ct.getOriginalRequest().getRequestURI());
					logger.logDebug("msg.Contact= " + notifyMessage.getContactHeader());
					logger.logDebug("msg.reqURI " + notifyMessage.getRequestURI());

				}

				if (fromTag.equalsIgnoreCase(thisToTag) && hisEvent != null && eventHdr.match(hisEvent)
						&& notifyMessage.getCallId().getCallId().equalsIgnoreCase(ct.getOriginalRequestCallId())) {
					// if (!this.isDeliverUnsolicitedNotify() ) {
					// ct.acquireSem();
					// }
					if (retval == null) {
						// take first matching tx, just in case
						retval = ct;
					}
					// https://github.com/RestComm/jain-sip/issues/60
					// Now check complementary conditions, to override selected ct, and break
					if ((ct.getOriginalRequest() != null && notifyMessage.getRequestURI()
							.equals(ct.getOriginalRequest().getContactHeader().getAddress().getURI()))
							&& (ct.getDefaultDialog() != null || ct.getDialog(dialogId) != null)) {
						if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
							logger.logDebug("Tx compl conditions met." + ct);
						}
						retval = ct;
						break;
					}

				}
			}

			return retval;
		} finally {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("findSubscribeTransaction : returning " + retval);

		}

	}

	/**
	 * Add entry to "Transaction Pending ACK" table.
	 *
	 * @param serverTransaction
	 */
	public void addTransactionPendingAck(SIPServerTransaction serverTransaction) {
		String branchId = ((SIPRequest) serverTransaction.getRequest()).getTopmostVia().getBranch();
		if (branchId != null) {
			this.terminatedServerTransactionsPendingAck.put(branchId, serverTransaction);
		}

	}

	/**
	 * Get entry in the server transaction pending ACK table corresponding to an
	 * ACK.
	 *
	 * @param ackMessage
	 * @return
	 */
	public SIPServerTransaction findTransactionPendingAck(SIPRequest ackMessage) {
		return this.terminatedServerTransactionsPendingAck.get(ackMessage.getTopmostVia().getBranch());
	}

	/**
	 * Remove entry from "Transaction Pending ACK" table.
	 *
	 * @param serverTransaction
	 * @return
	 */

	public boolean removeTransactionPendingAck(SIPServerTransaction serverTransaction) {
//        String branchId = ((SIPRequest)serverTransaction.getRequest()).getTopmostVia().getBranch();
		String branchId = serverTransaction.getBranchId();
		if (branchId != null && this.terminatedServerTransactionsPendingAck.containsKey(branchId)) {
			this.terminatedServerTransactionsPendingAck.remove(branchId);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Check if this entry exists in the "Transaction Pending ACK" table.
	 *
	 * @param serverTransaction
	 * @return
	 */
	public boolean isTransactionPendingAck(SIPServerTransaction serverTransaction) {
		String branchId = ((SIPRequest) serverTransaction.getRequest()).getTopmostVia().getBranch();
		return this.terminatedServerTransactionsPendingAck.contains(branchId);
	}

	/**
	 * Find the transaction corresponding to a given request.
	 *
	 * @param sipMessage request for which to retrieve the transaction.
	 *
	 * @param isServer   search the server transaction table if true.
	 *
	 * @return the transaction object corresponding to the request or null if no
	 *         such mapping exists.
	 */
	public SIPTransaction findTransaction(SIPMessage sipMessage, boolean isServer) {
		SIPTransaction retval = null;
		try {
			if (isServer) {
				Via via = sipMessage.getTopmostVia();
				if (via.getBranch() != null) {
					String key = sipMessage.getTransactionId();

					retval = (SIPTransaction) findTransaction(key, true);
					if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
						logger.logDebug("serverTx: looking for key " + key + " existing=" + serverTransactionTable);
					if (key.startsWith(SIPConstants.BRANCH_MAGIC_COOKIE_LOWER_CASE)) {
						return retval;
					}

				}
			} else {
				Via via = sipMessage.getTopmostVia();
				if (via.getBranch() != null) {
					String key = sipMessage.getTransactionId();
					if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
						logger.logDebug("clientTx: looking for key " + key);
					retval = (SIPTransaction) findTransaction(key, false);
					if (key.startsWith(SIPConstants.BRANCH_MAGIC_COOKIE_LOWER_CASE)) {
						return retval;
					}

				}
			}
		} finally {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("findTransaction: returning  : " + retval);
			}
		}
		return retval;

	}

	public SIPTransaction findTransaction(String transactionId, boolean isServer) {
		if (isServer) {
			if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
				logger.logDebug("Trying to find server transaction for branchID " + transactionId
						+ " serverTransactionTable " + serverTransactionTable);
			}
			return serverTransactionTable.get(transactionId);
		} else {
			if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
				logger.logDebug("Trying to find client transaction for branchID " + transactionId
						+ " clientTransactionTable " + clientTransactionTable);
			}
			return clientTransactionTable.get(transactionId);
		}
	}

	public Collection<SIPClientTransaction> getAllClientTransactions() {
		return clientTransactionTable.values();
	}

	public Collection<SIPServerTransaction> getAllServerTransactions() {
		return serverTransactionTable.values();
	}

	/**
	 * Get the transaction to cancel. Search the server transaction table for a
	 * transaction that matches the given transaction.
	 */
	public SIPTransaction findCancelTransaction(SIPRequest cancelRequest, boolean isServer) {

		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug(
					"findCancelTransaction request= \n" + cancelRequest + "\nfindCancelRequest isServer=" + isServer);
		}

		if (isServer) {
			Iterator<SIPServerTransaction> li = getAllServerTransactions().iterator();
			while (li.hasNext()) {
				SIPTransaction transaction = (SIPTransaction) li.next();

				SIPServerTransaction sipServerTransaction = (SIPServerTransaction) transaction;
				if (sipServerTransaction.doesCancelMatchTransaction(cancelRequest))
					return sipServerTransaction;
			}

		} else {
			Iterator<SIPClientTransaction> li = getAllClientTransactions().iterator();
			while (li.hasNext()) {
				SIPTransaction transaction = (SIPTransaction) li.next();

				SIPClientTransaction sipClientTransaction = (SIPClientTransaction) transaction;
				if (sipClientTransaction.doesCancelMatchTransaction(cancelRequest))
					return sipClientTransaction;

			}

		}
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
			logger.logDebug("Could not find transaction for cancel request");
		return null;
	}

	/**
	 * Construcor for the stack. Registers the request and response factories for
	 * the stack.
	 *
	 * @param messageFactory User-implemented factory for processing messages.
	 */
	protected SIPTransactionStack(StackMessageFactory messageFactory) {
		this();
		this.sipMessageFactory = messageFactory;
	}

	/**
	 * Finds a pending server transaction. Since each request may be handled either
	 * statefully or statelessly, we keep a map of pending transactions so that a
	 * duplicate transaction is not created if a second request is recieved while
	 * the first one is being processed.
	 *
	 * @param transactionId
	 * @return -- the pending transaction or null if no such transaction exists.
	 */
	public SIPServerTransaction findPendingTransaction(String transactionId) {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("looking for pending tx for :" + transactionId);
		}
		return pendingTransactions.get(transactionId);

	}

	/**
	 * See if there is a pending transaction with the same Merge ID as the Merge ID
	 * obtained from the SIP Request. The Merge table is for handling the following
	 * condition: If the request has no tag in the To header field, the UAS core
	 * MUST check the request against ongoing transactions. If the From tag,
	 * Call-ID, and CSeq exactly match those associated with an ongoing transaction,
	 * but the request does not match that transaction (based on the matching rules
	 * in Section 17.2.3), the UAS core SHOULD generate a 482 (Loop Detected)
	 * response and pass it to the server transaction.
	 */
	public boolean findMergedTransaction(SIPRequest sipRequest) {
		if (!sipRequest.getMethod().equals(Request.INVITE)) {
			/*
			 * Dont need to worry about request merging for Non-INVITE transactions.
			 */
			return false;
		}
		String mergeId = sipRequest.getMergeId();
		if (mergeId != null) {
			SIPServerTransaction mergedTransaction = findMergedTransactionByMergeId(mergeId);
			if (mergedTransaction != null && !mergedTransaction.isMessagePartOfTransaction(sipRequest)) {
				if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
					logger.logDebug(
							"Mathcing merged transaction for merge id " + mergeId + " with " + mergedTransaction);
				}
				return true;
			} else {
				/*
				 * Check for loop detection for really late arriving requests
				 */
				SIPDialog serverDialog = findMergedDialogByMergeId(mergeId);
				if (serverDialog != null && serverDialog.firstTransactionIsServerTransaction
						&& serverDialog.getState() == DialogState.CONFIRMED) {
					if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
						logger.logDebug("Mathcing merged dialog for merge id " + mergeId + " with " + serverDialog);
					}
					return true;
				}
			}
		}

		return false;
	}

	protected SIPServerTransaction findMergedTransactionByMergeId(String mergeId) {
		return this.mergeTable.get(mergeId);
	}

	protected SIPDialog findMergedDialogByMergeId(String mergeId) {
		return this.serverDialogMergeTestTable.get(mergeId);
	}

	/**
	 * Remove a pending Server transaction from the stack. This is called after the
	 * user code has completed execution in the listener.
	 *
	 * @param tr -- pending transaction to remove.
	 */
	public void removePendingTransaction(SIPServerTransaction tr) {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("removePendingTx: " + tr.getTransactionId());
		}
		this.pendingTransactions.remove(tr.getTransactionId());

	}

	/**
	 * Remove a transaction from the merge table.
	 *
	 * @param tr -- the server transaction to remove from the merge table.
	 *
	 */
	public void removeFromMergeTable(SIPServerTransaction tr) {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("Removing tx from merge table ");
		}
		// http://java.net/jira/browse/JSIP-429
		// get the merge id from the tx instead of the request to avoid reparsing on
		// aggressive cleanup
		String key = tr.getMergeId();
		if (key != null) {
			this.mergeTable.remove(key);
		}
	}

	/**
	 * Put this into the merge request table.
	 *
	 * @param sipTransaction -- transaction to put into the merge table.
	 *
	 */
	public void putInMergeTable(SIPServerTransaction sipTransaction, SIPRequest sipRequest) {
		String mergeKey = sipRequest.getMergeId();
		if (mergeKey != null) {
			this.mergeTable.put(mergeKey, sipTransaction);
		}
	}

	/**
	 * Map a Server transaction (possibly sending out a 100 if the server tx is an
	 * INVITE). This actually places it in the hash table and makes it known to the
	 * stack.
	 *
	 * @param transaction -- the server transaction to map.
	 */
	public void mapTransaction(SIPServerTransaction transaction) {
		if (transaction.isTransactionMapped())
			return;
		Transaction oldTx = addTransactionHash(transaction);
		if (oldTx == null) {
			// transaction.startTransactionTimer();
			transaction.setTransactionMapped(true);
		}
	}

	/**
	 * Handles a new SIP request. It finds a server transaction to handle this
	 * message. If none exists, it creates a new transaction.
	 *
	 * @param requestReceived       Request to handle.
	 * @param requestMessageChannel Channel that received message.
	 *
	 * @return A server transaction.
	 */
	public ServerRequestInterface newSIPServerRequest(SIPRequest requestReceived, SipProviderImpl sipProvider,
			MessageChannel requestMessageChannel) {
		// Next transaction in the set
		SIPServerTransaction nextTransaction;

		final String key = requestReceived.getTransactionId();

		requestReceived.setMessageChannel(requestMessageChannel);

		if (sipMessageValves.size() != 0) {
			// https://java.net/jira/browse/JSIP-511
			// catching all exceptions so it doesn't make JAIN SIP to fail
			try {
				for (SIPMessageValve sipMessageValve : this.sipMessageValves) {
					if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
						logger.logDebug("Checking SIP message valve " + sipMessageValve + " for Request = "
								+ requestReceived.getFirstLine());
					}
					if (!sipMessageValve.processRequest(requestReceived, requestMessageChannel)) {
						if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
							logger.logDebug("Request dropped by the SIP message valve. Request = " + requestReceived);
						}
						return null;
					}
				}
			} catch (Exception e) {
				if (logger.isLoggingEnabled(LogWriter.TRACE_ERROR)) {
					logger.logError("An issue happening the valve on request " + requestReceived
							+ " thus the message will not be processed further", e);
				}
				return null;
			}
		}

		// Transaction to handle this request
		SIPServerTransaction currentTransaction = (SIPServerTransaction) findTransaction(key, true);

		// Got to do this for bacasswards compatibility.
		if (currentTransaction == null || !currentTransaction.isMessagePartOfTransaction(requestReceived)) {

			// Loop through all server transactions
			currentTransaction = null;
			if (!key.toLowerCase().startsWith(SIPConstants.BRANCH_MAGIC_COOKIE_LOWER_CASE)) {
				Iterator<SIPServerTransaction> transactionIterator = getAllServerTransactions().iterator();
				while (transactionIterator.hasNext() && currentTransaction == null) {

					nextTransaction = (SIPServerTransaction) transactionIterator.next();

					// If this transaction should handle this request,
					if (nextTransaction.isMessagePartOfTransaction(requestReceived)) {
						// Mark this transaction as the one
						// to handle this message
						currentTransaction = nextTransaction;
					}
				}
			}

			// If no transaction exists to handle this message
			if (currentTransaction == null) {
				currentTransaction = findPendingTransaction(key);
				if (currentTransaction != null) {
					// Associate the tx with the received request.
					requestReceived.setTransaction(currentTransaction);
					// if (currentTransaction.acquireSem())
					return currentTransaction;
					// else
					// return null;

				}
				// Creating a new server tx. May fail under heavy load.
				if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
					logger.logDebug("No transaction found " + currentTransaction
							+ " Creating new server transaction for request " + requestReceived);
				}
				currentTransaction = createServerTransaction(sipProvider, requestMessageChannel);
				if (currentTransaction != null) {
					// currentTransaction.setPassToListener();
					currentTransaction.setOriginalRequest(requestReceived);
					// Associate the tx with the received request.
					requestReceived.setTransaction(currentTransaction);
				}

			}

		}

		// Set ths transaction's encapsulated request
		// interface from the superclass
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("newSIPServerRequest( " + requestReceived.getMethod() + ":"
					+ requestReceived.getTopmostVia().getBranch() + "):" + currentTransaction);
		}

		if (currentTransaction != null)
			currentTransaction
					.setRequestInterface(sipMessageFactory.newSIPServerRequest(requestReceived, currentTransaction));

		if (currentTransaction != null) {
			// && currentTransaction.acquireSem()) {
			return currentTransaction;
		}
		// else if (currentTransaction != null) {
		// try {
		// /*
		// * Already processing a message for this transaction. SEND a
		// * trying ( message already being processed ).
		// */
		// if (currentTransaction
		// .isMessagePartOfTransaction(requestReceived)
		// && currentTransaction.getMethod().equals(
		// requestReceived.getMethod())) {
		// SIPResponse trying = requestReceived
		// .createResponse(Response.TRYING);
		// trying.removeContent();
		// currentTransaction.getMessageChannel().sendMessage(trying);
		// }
		// } catch (Exception ex) {
		// if (logger.isLoggingEnabled())
		// logger.logError("Exception occured sending TRYING");
		// }
		// return null;
		// }
		else {
			return null;
		}
	}

	/**
	 * Handles a new SIP response. It finds a client transaction to handle this
	 * message. If none exists, it sends the message directly to the superclass.
	 *
	 * @param responseReceived       Response to handle.
	 * @param responseMessageChannel Channel that received message.
	 *
	 * @return A client transaction.
	 */
	public ServerResponseInterface newSIPServerResponse(SIPResponse responseReceived,
			MessageChannel responseMessageChannel) {

		// Iterator through all client transactions
		Iterator<SIPClientTransaction> transactionIterator;
		// Next transaction in the set
		SIPClientTransaction nextTransaction;
		// Transaction to handle this request
		SIPClientTransaction currentTransaction;

		if (sipMessageValves.size() != 0) {
			// https://java.net/jira/browse/JSIP-511
			// catching all exceptions so it doesn't make JAIN SIP to fail
			try {
				for (SIPMessageValve sipMessageValve : this.sipMessageValves) {
					if (!sipMessageValve.processResponse(responseReceived, responseMessageChannel)) {
						if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
							logger.logDebug(
									"Response dropped by the SIP message valve. Response = " + responseReceived);
						}
						return null;
					}
				}
			} catch (Exception e) {
				if (logger.isLoggingEnabled(LogWriter.TRACE_ERROR)) {
					logger.logError("An issue happening the valve on response " + responseReceived
							+ " thus the message will not be processed further", e);
				}
				return null;
			}
		}

		String key = responseReceived.getTransactionId();

		// Note that for RFC 3261 compliant operation, this lookup will
		// return a tx if one exists and hence no need to search through
		// the table.
		currentTransaction = (SIPClientTransaction) findTransaction(key, false);

		if (currentTransaction == null || (!currentTransaction.isMessagePartOfTransaction(responseReceived)
				&& !key.startsWith(SIPConstants.BRANCH_MAGIC_COOKIE_LOWER_CASE))) {
			// Loop through all client transactions

			transactionIterator = getAllClientTransactions().iterator();
			currentTransaction = null;
			while (transactionIterator.hasNext() && currentTransaction == null) {

				nextTransaction = (SIPClientTransaction) transactionIterator.next();

				// If this transaction should handle this request,
				if (nextTransaction.isMessagePartOfTransaction(responseReceived)) {

					// Mark this transaction as the one to
					// handle this message
					currentTransaction = nextTransaction;

				}

			}

			// If no transaction exists to handle this message,
			if (currentTransaction == null) {
				// JvB: Need to log before passing the response to the client
				// app, it
				// gets modified!
				if (logger.isLoggingEnabled(StackLogger.TRACE_INFO)) {
					responseMessageChannel.logResponse(responseReceived, System.currentTimeMillis(),
							"before processing");
				}

				// Pass the message directly to the TU
				return sipMessageFactory.newSIPServerResponse(responseReceived, responseMessageChannel);

			}
		}

		if (currentTransaction != null && currentTransaction.getMessageChannel() == null) {
			currentTransaction.setEncapsulatedChannel(responseMessageChannel);
		}

		// Aquire the sem -- previous request may still be processing.
		// boolean acquired = currentTransaction.acquireSem();
		// Set ths transaction's encapsulated response interface
		// from the superclass
		if (logger.isLoggingEnabled(StackLogger.TRACE_INFO)) {
			currentTransaction.getMessageChannel().logResponse(responseReceived, System.currentTimeMillis(),
					"before processing");
		}

		// if (acquired) {
		ServerResponseInterface sri = sipMessageFactory.newSIPServerResponse(responseReceived,
				currentTransaction.getMessageChannel());
		if (sri != null) {
			currentTransaction.setResponseInterface(sri);
		} else {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("returning null - serverResponseInterface is null!");
			}
			// currentTransaction.releaseSem();
			return null;
		}
		// } else {
		// logger.logWarning(
		// "Application is blocked -- could not acquire semaphore -- dropping
		// response");
		// }

		// if (acquired)
		return currentTransaction;
		// else
		// return null;

	}

	/**
	 * Creates a client transaction to handle a new request. Gets the real message
	 * channel from the superclass, and then creates a new client transaction
	 * wrapped around this channel.
	 *
	 * @param nextHop Hop to create a channel to contact.
	 */
	public MessageChannel createMessageChannel(SIPRequest request, MessageProcessor mp, Hop nextHop)
			throws IOException {

		// Create a new client transaction around the
		// superclass' message channel
		// Create the host/port of the target hop
		Host targetHost = new Host();
		targetHost.setHostname(nextHop.getHost());
		HostPort targetHostPort = new HostPort();
		targetHostPort.setHost(targetHost);
		targetHostPort.setPort(nextHop.getPort());
		MessageChannel returnChannel = mp.createMessageChannel(targetHostPort);
		return returnChannel;

	}

	/**
	 * Creates a client transaction that encapsulates a MessageChannel. Useful for
	 * implementations that want to subclass the standard
	 *
	 * @param encapsulatedMessageChannel Message channel of the transport layer.
	 */
	public SIPClientTransaction createClientTransaction(SIPRequest sipRequest, SipProviderImpl sipProvider,
			MessageChannel encapsulatedMessageChannel) {
		SIPClientTransaction ct = createNewClientTransaction(sipRequest, sipProvider, encapsulatedMessageChannel);
		ct.setOriginalRequest(sipRequest);
		return ct;
	}

	/**
	 * Creates a server transaction that encapsulates a MessageChannel. Useful for
	 * implementations that want to subclass the standard
	 *
	 * @param encapsulatedMessageChannel Message channel of the transport layer.
	 */
	public SIPServerTransaction createServerTransaction(SipProviderImpl sipProvider,
			MessageChannel encapsulatedMessageChannel) {
		// Issue 256 : be consistent with createClientTransaction, if
		// unlimitedServerTransactionTableSize is true,
		// a new Server Transaction is created no matter what
		if (unlimitedServerTransactionTableSize) {
			return createNewServerTransaction(sipProvider, encapsulatedMessageChannel);
		} else {
			float threshold = ((float) (getServerTransactionTableSize() - serverTransactionTableLowaterMark))
					/ ((float) (serverTransactionTableHighwaterMark - serverTransactionTableLowaterMark));
			boolean decision = Math.random() > 1.0 - threshold;
			if (decision) {
				return null;
			} else {
				return createNewServerTransaction(sipProvider, encapsulatedMessageChannel);
			}

		}
	}

	/**
	 * Creates a new client transaction that encapsulates a MessageChannel without
	 * any additional logic. Useful for implementations that want to subclass the
	 * standard
	 *
	 * @param encapsulatedMessageChannel Message channel of the transport layer.
	 */
	public SIPClientTransactionImpl createNewClientTransaction(SIPRequest sipRequest, SipProviderImpl sipProvider,
			MessageChannel encapsulatedMessageChannel) {
		return new SIPClientTransactionImpl(this, sipProvider, encapsulatedMessageChannel);
	}

	/**
	 * Creates a new server transaction that encapsulates a MessageChannel without
	 * any additional logic. Useful for implementations that want to subclass the
	 * standard
	 *
	 * @param encapsulatedMessageChannel Message channel of the transport layer.
	 */
	public SIPServerTransactionImpl createNewServerTransaction(SipProviderImpl sipProvider,
			MessageChannel encapsulatedMessageChannel) {
		return new SIPServerTransactionImpl(this, sipProvider, encapsulatedMessageChannel);
	}

	/**
	 * Get the size of the client transaction table.
	 *
	 * @return -- size of the ct table.
	 */
	public int getClientTransactionTableSize() {
		return this.clientTransactionTable.size();
	}

	/**
	 * Get the size of the server transaction table.
	 *
	 * @return -- size of the server table.
	 */
	public int getServerTransactionTableSize() {
		return this.serverTransactionTable.size();
	}

	/**
	 * Add a new client transaction to the set of existing transactions. Add it to
	 * the top of the list so an incoming response has less work to do in order to
	 * find the transaction.
	 *
	 * @param clientTransaction -- client transaction to add to the set.
	 */
	public SIPTransaction addTransaction(SIPClientTransaction clientTransaction) {
		SIPTransaction oldTx = addTransactionHash(clientTransaction);
		if (oldTx == null) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("added transaction " + clientTransaction);
		}
		return oldTx;

	}

	/**
	 * Remove transaction. This actually gets the tx out of the search structures
	 * which the stack keeps around. When the tx
	 */
	public SIPTransaction removeTransaction(SIPTransaction sipTransaction) {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("removeTransaction: Removing Transaction = " + sipTransaction.getTransactionId()
					+ " transaction = " + sipTransaction);
		}
		SIPTransaction removed = null;
		try {
			if (sipTransaction instanceof SIPServerTransaction) {
				if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
					logger.logStackTrace();
				}
				String key = sipTransaction.getTransactionId();
				removed = removeTransactionById(key, true);
				String method = sipTransaction.getMethod();
				this.removePendingTransaction((SIPServerTransaction) sipTransaction);
				this.removeTransactionPendingAck((SIPServerTransaction) sipTransaction);
				if (method.equalsIgnoreCase(Request.INVITE)) {
					this.removeFromMergeTable((SIPServerTransaction) sipTransaction);
				}
				// Send a notification to the listener.
				SipProviderImpl sipProvider = (SipProviderImpl) sipTransaction.getSipProvider();
				if (removed != null && sipTransaction.testAndSetTransactionTerminatedEvent()) {
					TransactionTerminatedEvent event = new TransactionTerminatedEvent(sipProvider,
							(ServerTransaction) sipTransaction);

					sipProvider.handleEvent(event, sipTransaction);

				}
			} else {

				String key = sipTransaction.getTransactionId();
				removed = findTransaction(key, false);

				if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
					logger.logDebug("client tx to be removed " + removed + " KEY = " + key);
				}
				if (removed != null) {
					SIPClientTransaction clientTx = (SIPClientTransaction) removed;
					final String forkId = clientTx.getForkId();
					if (forkId != null && clientTx.isInviteTransaction() && this.maxForkTime != 0) {
						if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
							logger.logDebug("Scheduling to remove forked client transaction : forkId = " + forkId
									+ " in " + this.maxForkTime + " seconds");
						}
						this.timer.schedule(new RemoveForkedTransactionTimerTask(clientTx.getOriginalRequestCallId(),
								key, forkId, sipTransaction.getSipProvider()), this.maxForkTime * 1000);

						clientTx.stopExpiresTimer();
					} else {
						removed = removeTransactionById(key, false);
						sendTransactionTerminatedEvent(removed, sipTransaction.getSipProvider());
					}
				}
			}
		} finally {
			// http://java.net/jira/browse/JSIP-420
			if (removed != null) {
				((SIPTransaction) removed).cancelMaxTxLifeTimeTimer();
			}
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug(String.format("removeTransaction: Table size : " + " clientTransactionTable %d "
						+ " serverTransactionTable %d " + " mergetTable %d "
						+ " terminatedServerTransactionsPendingAck %d  " +
						// " forkedClientTransactionTable %d " +
						" pendingTransactions %d ", getClientTransactionTableSize(), getServerTransactionTableSize(),
						mergeTable.size(), terminatedServerTransactionsPendingAck.size(),
						// forkedClientTransactionTable.size(),
						pendingTransactions.size()));
			}
		}
		return removed;
	}

	private void sendTransactionTerminatedEvent(SIPTransaction sipTransaction, SipProvider sipProvider) {
		// Send a notification to the listener.
		if (sipTransaction != null && sipTransaction.testAndSetTransactionTerminatedEvent()) {

			TransactionTerminatedEvent event = new TransactionTerminatedEvent(sipProvider,
					(ClientTransaction) sipTransaction);

			((SipProviderImpl) sipProvider).handleEvent(event, sipTransaction);
		}
	}

	/**
	 * Add a new server transaction to the set of existing transactions. Add it to
	 * the top of the list so an incoming ack has less work to do in order to find
	 * the transaction.
	 *
	 * @param serverTransaction -- server transaction to add to the set.
	 */
	public SIPTransaction addTransaction(SIPServerTransaction serverTransaction) {
		SIPTransaction oldTx = addTransactionHash(serverTransaction);
		if (oldTx == null) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
				logger.logDebug("added transaction " + serverTransaction);
			serverTransaction.map();
		}
		return oldTx;

	}

	/**
	 * Hash table for quick lookup of transactions. Here we wait for room if needed.
	 */
	private SIPTransaction addTransactionHash(SIPTransaction sipTransaction) {
		SIPRequest sipRequest = sipTransaction.getOriginalRequest();
		SIPTransaction existingTx = null;
		if (sipTransaction instanceof SIPClientTransaction) {
			if (!this.unlimitedClientTransactionTableSize) {
				if (this.activeClientTransactionCount.get() > clientTransactionTableHiwaterMark) {
					// try {
					// Doesn't make sense to wait in real time systems
					// We are returning the tx if it already exists

					// synchronized (this.clientTransactionTable) {
					// this.clientTransactionTable.wait();
					// this.activeClientTransactionCount.incrementAndGet();
					// }

					String key = sipRequest.getTransactionId();
					existingTx = findTransaction(key, false);

					return existingTx;
					// } catch (Exception ex) {
					// if (logger.isLoggingEnabled()) {
					// logger.logError(
					// "Exception occured while waiting for room",
					// ex);
					// }

					// }
				}
			} else {
				this.activeClientTransactionCount.incrementAndGet();
			}
			String key = sipRequest.getTransactionId();
			existingTx = storeTransaction(key, sipTransaction, false);

			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug(" putTransactionHash : " + " key = " + key);
			}
		} else {
			String key = sipRequest.getTransactionId();

			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug(" putTransactionHash : " + " key = " + key);
			}
			existingTx = storeTransaction(key, sipTransaction, true);

		}
		// http://java.net/jira/browse/JSIP-420
		if (existingTx == null) {
			sipTransaction.scheduleMaxTxLifeTimeTimer();
		}
		return existingTx;
	}

	protected SIPTransaction storeTransaction(String key, SIPTransaction sipTransaction, boolean isServer) {
		try {
			if (isServer) {
				return serverTransactionTable.putIfAbsent(key, (SIPServerTransaction) sipTransaction);
			} else {
				return clientTransactionTable.putIfAbsent(key, (SIPClientTransaction) sipTransaction);
			}
		} finally {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("STORED tx " + sipTransaction + " KEY = " + key + " isServer = " + isServer);
			}
		}
	}

	/**
	 * This method is called when a client tx transitions to the Completed or
	 * Terminated state.
	 *
	 */
	protected void decrementActiveClientTransactionCount() {
		this.activeClientTransactionCount.decrementAndGet();

		// if (this.activeClientTransactionCount.decrementAndGet() <=
		// this.clientTransactionTableLowaterMark
		// && !this.unlimitedClientTransactionTableSize) {
		// synchronized (this.clientTransactionTable) {

		// clientTransactionTable.notify();

		// }
		// }
	}

	/**
	 * Remove the transaction from transaction hash.
	 */
	protected SIPTransaction removeTransactionHash(SIPTransaction sipTransaction) {
		SIPRequest sipRequest = sipTransaction.getOriginalRequest();
		if (sipRequest == null)
			return null;
		SIPTransaction removed = null;
		if (sipTransaction instanceof SIPClientTransaction) {
			String key = sipTransaction.getTransactionId();
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logStackTrace();
				logger.logDebug("removing client Tx : " + key);
			}
			removed = removeTransactionById(key, false);

		} else if (sipTransaction instanceof SIPServerTransaction) {
			String key = sipTransaction.getTransactionId();
			removed = removeTransactionById(key, true);
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("removing server Tx : " + key);
			}
		}
		// http://java.net/jira/browse/JSIP-420
		if (removed != null) {
			((SIPTransaction) removed).cancelMaxTxLifeTimeTimer();
		}
		return removed;
	}

	protected SIPTransaction removeTransactionById(String transactionId, boolean isServer) {
		SIPTransaction sipTransaction = null;
		if (isServer) {
			sipTransaction = serverTransactionTable.remove(transactionId);
		} else {
			sipTransaction = clientTransactionTable.remove(transactionId);
		}
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("REMOVED tx " + sipTransaction + " KEY = " + transactionId + " isServer = " + isServer);
		}
		return sipTransaction;
	}

	/**
	 * Invoked when an error has ocurred with a transaction.
	 *
	 * @param transactionErrorEvent Error event.
	 */
	public void transactionErrorEvent(SIPTransactionErrorEvent transactionErrorEvent) {
		SIPTransaction transaction = (SIPTransaction) transactionErrorEvent.getSource();

		if (transactionErrorEvent.getErrorID() == SIPTransactionErrorEvent.TRANSPORT_ERROR) {
			// Kill scanning of this transaction.
			transaction.setState(TransactionState._TERMINATED);
			if (transaction instanceof SIPServerTransaction) {
				// let the reaper get him
				((SIPServerTransaction) transaction).setCollectionTime(0);
			}
			transaction.disableTimeoutTimer();
			transaction.disableRetransmissionTimer();
			// Send a IO Exception to the Listener.
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see gov.nist.javax.sip.stack.SIPDialogEventListener#dialogErrorEvent(gov.
	 * nist.javax.sip.stack.SIPDialogErrorEvent)
	 */
	public void dialogErrorEvent(SIPDialogErrorEvent dialogErrorEvent) {
		SIPDialog sipDialog = (SIPDialog) dialogErrorEvent.getSource();
		SipListener sipListener = ((SipStackImpl) this).getSipListener();
		// if the app is not implementing the SipListenerExt interface we delete
		// the dialog to avoid leaks
		if (sipDialog != null && !(sipListener instanceof SipListenerExt)) {
			sipDialog.delete();
		}
	}

	/**
	 * Stop stack. Clear all the timer stuff. Make the stack close all accept
	 * connections and return. This is useful if you want to start/stop the stack
	 * several times from your application. Caution : use of this function could
	 * cause peculiar bugs as messages are prcessed asynchronously by the stack.
	 */
	public void stopStack() {
		if (!toExit && this.messageProcessorExecutor != null) {
			messageProcessorExecutor.stop();
			messageProcessorExecutor = null;
		}
		// Prevent NPE on two concurrent stops
		this.toExit = true;

		// JvB: set it to null, SIPDialog tries to schedule things after stop
		this.pendingTransactions.clear();
		// synchronized (this) {
		// this.notifyAll();
		// }
		// synchronized (this.clientTransactionTable) {
		// clientTransactionTable.notifyAll();
		// }

		// Threads must periodically check this flag.
		MessageProcessor[] processorList;
		processorList = getMessageProcessors();
		for (int processorIndex = 0; processorIndex < processorList.length; processorIndex++) {
			removeMessageProcessor(processorList[processorIndex]);
		}
		closeAllSockets();
		// Let the processing complete.

		if (this.timer != null) {
			this.timer.stop();
		}
		try {
			Thread.sleep(1000);
		} catch (InterruptedException ex) {
		}
		this.clientTransactionTable.clear();
		this.serverTransactionTable.clear();

		this.dialogTable.clear();
		this.serverLogger.closeLogFile();
	}

	public void closeAllSockets() {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("Closing message processors, IOHandlers and message channels");
		}
		for (MessageProcessor p : messageProcessors.values()) {
			if (p instanceof TCPMessageProcessor) {
				TCPMessageProcessor tcp = (TCPMessageProcessor) p;
				tcp.getIOHandler().closeAll();
			}
			if (p instanceof TLSMessageProcessor) {
				TLSMessageProcessor tcp = (TLSMessageProcessor) p;
				tcp.getIOHandler().closeAll();
			}
			if (p instanceof NioTcpMessageProcessor) {
				NioTcpMessageProcessor niop = (NioTcpMessageProcessor) p;
				niop.getNioHandler().closeAll();
			}
			if (p instanceof NioTlsMessageProcessor) {
				NioTlsMessageProcessor niop = (NioTlsMessageProcessor) p;
				niop.getNioHandler().closeAll();
			}
			if (p instanceof NettyStreamMessageProcessor) {
				NettyStreamMessageProcessor nettyStreamMessageProcessor = (NettyStreamMessageProcessor) p;
				nettyStreamMessageProcessor.close();
			}
		}
	}

	/**
	 * Put a transaction in the pending transaction list. This is to avoid a race
	 * condition when a duplicate may arrive when the application is deciding
	 * whether to create a transaction or not.
	 */
	public void putPendingTransaction(SIPServerTransaction tr) {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
			logger.logDebug("putPendingTransaction: " + tr);

		this.pendingTransactions.put(tr.getTransactionId(), tr);

	}

	/**
	 * Return the network layer (i.e. the interface for socket creation or the
	 * socket factory for the stack).
	 *
	 * @return -- the registered Network Layer.
	 */
	public NetworkLayer getNetworkLayer() {
		if (networkLayer == null) {
			return DefaultNetworkLayer.SINGLETON;
		} else {
			return networkLayer;
		}
	}

	/**
	 * Return true if logging is enabled for this stack. Deprecated. Use
	 * StackLogger.isLoggingEnabled instead
	 *
	 * @return true if logging is enabled for this stack instance.
	 */
	@Deprecated
	public boolean isLoggingEnabled() {
		return logger == null ? false : logger.isLoggingEnabled();
	}

	/**
	 * Deprecated. Use StackLogger.isLoggingEnabled instead
	 * 
	 * @param level
	 * @return
	 */
	@Deprecated
	public boolean isLoggingEnabled(int level) {
		return logger == null ? false : logger.isLoggingEnabled(level);
	}

	/**
	 * Get the logger. This method should be deprected. Use static logger =
	 * CommonLogger.getLogger() instead
	 *
	 * @return --the logger for the sip stack. Each stack has its own logger
	 *         instance.
	 */
	@Deprecated
	public StackLogger getStackLogger() {
		return logger;
	}

	/**
	 * Server log is the place where we log messages for the signaling trace viewer.
	 *
	 * @return -- the log file where messages are logged for viewing by the trace
	 *         viewer.
	 */
	public ServerLogger getServerLogger() {
		return this.serverLogger;
	}

	/**
	 * Maximum size of a single TCP message. Limiting the size of a single TCP
	 * message prevents flooding attacks. MAX TCP message size is effective for both
	 * directions for netty handlers
	 *
	 * @return the size of a single TCP message.
	 */
	public int getMaxMessageSize() {
		return this.maxMessageSize;
	}

	/**
	 * Maximum size of a single UDP message. Limiting the size of a single UDP
	 * message prevents datagram fragmentation which in turns can cause
	 * handling/processing errors. MAX UDP message size is effective for both
	 * directions for netty handlers
	 *
	 * @return the size of a single UDP message.
	 */
	public int getMaxUdpMessageSize() {
		return this.maxUdpMessageSize;
	}

	/**
	 * Set the flag that instructs the stack to only start a single thread for
	 * sequentially processing incoming udp messages (thus serializing the
	 * processing). Same as setting thread pool size to 1.
	 */
	public void setSingleThreaded() {
		this.threadPoolSize = 1;
	}

	/**
	 * Set the thread pool size for processing incoming UDP messages. Limit the
	 * total number of threads for processing udp messages.
	 *
	 * @param size -- the thread pool size.
	 *
	 */
	public void setThreadPoolSize(int size) {
		this.threadPoolSize = size;
	}

	/**
	 * Set the max # of simultaneously handled TCP connections.
	 *
	 * @param nconnections -- the number of connections to handle.
	 */
	public void setMaxConnections(int nconnections) {
		this.maxConnections = nconnections;
	}

	/**
	 * 
	 * @return
	 */
	public int getMaxConnections() {
		return maxConnections;
	}

	/**
	 * Get the default route string.
	 *
	 * @param sipRequest is the request for which we want to compute the next hop.
	 * @throws SipException
	 */
	public Hop getNextHop(SIPRequest sipRequest) throws SipException {
		if (this.useRouterForAll) {
			// Use custom router to route all messages.
			if (router != null)
				return router.getNextHop(sipRequest);
			else
				return null;
		} else {
			// Also non-SIP request containing Route headers goes to the default
			// router
			if (sipRequest.getRequestURI().isSipURI() || sipRequest.getRouteHeaders() != null) {
				return defaultRouter.getNextHop(sipRequest);
			} else if (router != null) {
				return router.getNextHop(sipRequest);
			} else
				return null;
		}
	}

	/**
	 * Set the descriptive name of the stack.
	 *
	 * @param stackName -- descriptive name of the stack.
	 */
	public void setStackName(String stackName) {
		this.stackName = stackName;
	}

	/**
	 * Set my address.
	 *
	 * @param stackAddress -- A string containing the stack address.
	 */
	protected void setHostAddress(String stackAddress) throws UnknownHostException {
		if (stackAddress.indexOf(':') != stackAddress.lastIndexOf(':') && stackAddress.trim().charAt(0) != '[')
			this.stackAddress = '[' + stackAddress + ']';
		else
			this.stackAddress = stackAddress;
		this.stackInetAddress = InetAddress.getByName(stackAddress);
	}

	/**
	 * Get my address.
	 *
	 * @return hostAddress - my host address or null if no host address is defined.
	 * @deprecated
	 */
	public String getHostAddress() {

		// JvB: for 1.2 this may return null...
		return this.stackAddress;
	}

	/**
	 * Set the router algorithm. This is meant for routing messages out of dialog or
	 * for non-sip uri's.
	 *
	 * @param router A class that implements the Router interface.
	 */
	protected void setRouter(Router router) {
		this.router = router;
	}

	/**
	 * Get the router algorithm.
	 *
	 * @return Router router
	 */
	public Router getRouter(SIPRequest request) {
		if (request.getRequestLine() == null) {
			return this.defaultRouter;
		} else if (this.useRouterForAll) {
			return this.router;
		} else {
			if (request.getRequestURI().getScheme().equals("sip")
					|| request.getRequestURI().getScheme().equals("sips")) {
				return this.defaultRouter;
			} else {
				if (this.router != null)
					return this.router;
				else
					return defaultRouter;
			}
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see javax.sip.SipStack#getRouter()
	 */
	public Router getRouter() {
		return this.router;
	}

	/**
	 * return the status of the toExit flag.
	 *
	 * @return true if the stack object is alive and false otherwise.
	 */
	public boolean isAlive() {
		return !toExit;
	}

	/**
	 * Adds a new MessageProcessor to the list of running processors for this
	 * SIPStack and starts it. You can use this method for dynamic stack
	 * configuration.
	 */
	protected void addMessageProcessor(MessageProcessor newMessageProcessor) throws IOException {
		// Suggested changes by Jeyashankher, jai@lucent.com
		// newMessageProcessor.start() can fail
		// because a local port is not available
		// This throws an IOException.
		// We should not add the message processor to the
		// local list of processors unless the start()
		// call is successful.
		// newMessageProcessor.start();
		messageProcessors.putIfAbsent(newMessageProcessor.getKey(), newMessageProcessor);

	}

	/**
	 * Removes a MessageProcessor from this SIPStack.
	 *
	 * @param oldMessageProcessor
	 */
	protected void removeMessageProcessor(MessageProcessor oldMessageProcessor) {
		if (messageProcessors.remove(oldMessageProcessor.getKey()) != null) {
			oldMessageProcessor.stop();
		}
	}

	/**
	 * Gets an array of running MessageProcessors on this SIPStack. Acknowledgement:
	 * Jeff Keyser suggested that applications should have access to the running
	 * message processors and contributed this code.
	 *
	 * @return an array of running message processors.
	 */
	protected MessageProcessor[] getMessageProcessors() {
		return (MessageProcessor[]) messageProcessors.values().toArray(new MessageProcessor[0]);

	}

	/**
	 * Creates the equivalent of a JAIN listening point and attaches to the stack.
	 *
	 * @param ipAddress -- ip address for the listening point.
	 * @param port      -- port for the listening point.
	 * @param transport -- transport for the listening point.
	 */
	protected MessageProcessor createMessageProcessor(InetAddress ipAddress, int port, String transport)
			throws java.io.IOException {
		MessageProcessor newMessageProcessor = messageProcessorFactory.createMessageProcessor(this, ipAddress, port,
				transport);
		this.addMessageProcessor(newMessageProcessor);
		return newMessageProcessor;
	}

	/**
	 * Set the message factory.
	 *
	 * @param messageFactory -- messageFactory to set.
	 */
	protected void setMessageFactory(StackMessageFactory messageFactory) {
		this.sipMessageFactory = messageFactory;
	}

	/**
	 * Creates a new MessageChannel for a given Hop.
	 *
	 * @param sourceIpAddress - Ip address of the source of this message.
	 *
	 * @param sourcePort      - source port of the message channel to be created.
	 *
	 * @param nextHop         Hop to create a MessageChannel to.
	 *
	 * @return A MessageChannel to the specified Hop, or null if no
	 *         MessageProcessors support contacting that Hop.
	 *
	 * @throws UnknownHostException If the host in the Hop doesn't exist.
	 */
	public MessageChannel createRawMessageChannel(String sourceIpAddress, int sourcePort, Hop nextHop)
			throws UnknownHostException {
		Host targetHost;
		HostPort targetHostPort;
		Iterator<MessageProcessor> processorIterator;
		MessageProcessor nextProcessor;
		MessageChannel newChannel;

		// Create the host/port of the target hop
		targetHost = new Host();
		targetHost.setHostname(nextHop.getHost());
		targetHostPort = new HostPort();
		targetHostPort.setHost(targetHost);
		targetHostPort.setPort(nextHop.getPort());

		// Search each processor for the correct transport
		newChannel = null;
		processorIterator = messageProcessors.values().iterator();
		while (processorIterator.hasNext() && newChannel == null) {
			nextProcessor = (MessageProcessor) processorIterator.next();
			// If a processor that supports the correct
			// transport is found,
			if (nextHop.getTransport().equalsIgnoreCase(nextProcessor.getTransport())
					&& sourceIpAddress.equals(nextProcessor.getIpAddress().getHostAddress())
					&& sourcePort == nextProcessor.getPort()) {
				try {
					// Create a channel to the target
					// host/port
					newChannel = nextProcessor.createMessageChannel(targetHostPort);
				} catch (UnknownHostException ex) {
					if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
						logger.logDebug("host is not known " + targetHostPort + " " + ex.getMessage());
					throw ex;
				} catch (IOException e) {
					if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
						logger.logDebug("host is reachable " + targetHostPort + " " + e.getMessage());
					// Ignore channel creation error -
					// try next processor
				}
			}
		}

		if (newChannel == null) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("newChanne is null, messageProcessors.size = " + messageProcessors.size());
				processorIterator = messageProcessors.values().iterator();
				while (processorIterator.hasNext() && newChannel == null) {
					nextProcessor = (MessageProcessor) processorIterator.next();
					logger.logDebug("nextProcessor:" + nextProcessor + "| transport = " + nextProcessor.getTransport()
							+ " ipAddress=" + nextProcessor.getIpAddress() + " port=" + nextProcessor.getPort());
				}
				logger.logDebug("More info on newChannel=null");
				logger.logDebug("nextHop=" + nextHop + " sourceIp=" + sourceIpAddress + " sourcePort=" + sourcePort
						+ " targetHostPort=" + targetHostPort);
			}
		}
		// Return the newly-created channel
		return newChannel;
	}

	/**
	 * Return true if a given event can result in a forked subscription. The stack
	 * is configured with a set of event names that can result in forked
	 * subscriptions.
	 *
	 * @param ename -- event name to check.
	 *
	 */
	public boolean isEventForked(String ename) {
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("isEventForked: " + ename + " returning " + this.forkedEvents.contains(ename));
		}
		return this.forkedEvents.contains(ename);
	}

	/**
	 * get the address resolver interface.
	 *
	 * @return -- the registered address resolver.
	 */
	public AddressResolver getAddressResolver() {
		return this.addressResolver;
	}

	/**
	 * Set the address resolution interface
	 *
	 * @param addressResolver -- the address resolver to set.
	 */
	public void setAddressResolver(AddressResolver addressResolver) {
		this.addressResolver = addressResolver;
	}

	/**
	 * Set the logger factory.
	 *
	 * @param logRecordFactory -- the log record factory to set.
	 */
	public void setLogRecordFactory(LogRecordFactory logRecordFactory) {
		this.logRecordFactory = logRecordFactory;
	}

	/**
	 * get the thread auditor object
	 *
	 * @return -- the thread auditor of the stack
	 */
	public ThreadAuditor getThreadAuditor() {
		return this.threadAuditor;
	}

	// /
	// / Stack Audit methods
	// /

	/**
	 * Audits the SIP Stack for leaks
	 *
	 * @return Audit report, null if no leaks were found
	 */
	public String auditStack(Set<String> activeCallIDs, long leakedDialogTimer, long leakedTransactionTimer) {
		String auditReport = null;
		String leakedDialogs = auditDialogs(activeCallIDs, leakedDialogTimer);
		String leakedServerTransactions = auditTransactions(serverTransactionTable, leakedTransactionTimer);
		String leakedClientTransactions = auditTransactions(clientTransactionTable, leakedTransactionTimer);
		if (leakedDialogs != null || leakedServerTransactions != null || leakedClientTransactions != null) {
			auditReport = "SIP Stack Audit:\n" + (leakedDialogs != null ? leakedDialogs : "")
					+ (leakedServerTransactions != null ? leakedServerTransactions : "")
					+ (leakedClientTransactions != null ? leakedClientTransactions : "");
		}
		return auditReport;
	}

	/**
	 * Audits SIP dialogs for leaks - Compares the dialogs in the dialogTable with a
	 * list of Call IDs passed by the application. - Dialogs that are not known by
	 * the application are leak suspects. - Kill the dialogs that are still around
	 * after the timer specified.
	 *
	 * @return Audit report, null if no dialog leaks were found
	 */
	private String auditDialogs(Set<String> activeCallIDs, long leakedDialogTimer) {
		String auditReport = "  Leaked dialogs:\n";
		int leakedDialogs = 0;
		long currentTime = System.currentTimeMillis();

		// Make a shallow copy of the dialog list.
		// This copy will remain intact as leaked dialogs are removed by the
		// stack.
		ConcurrentHashMap<String, SIPDialog> dialogs = new ConcurrentHashMap<String, SIPDialog>(dialogTable);
		;
		// synchronized (dialogTable) {
		// dialogs = new ConcurrentHashMap<String, SIPDialog>(dialogTable);
		// }

		// Iterate through the dialogDialog, get the callID of each dialog and
		// check if it's in the
		// list of active calls passed by the application. If it isn't, start
		// the timer on it.
		// If the timer has expired, kill the dialog.
		Iterator<SIPDialog> it = dialogs.values().iterator();
		while (it.hasNext()) {
			// Get the next dialog
			SIPDialog itDialog = it.next();

			// Get the call id associated with this dialog
			CallIdHeader callIdHeader = (itDialog != null ? itDialog.getCallId() : null);
			String callID = (callIdHeader != null ? callIdHeader.getCallId() : null);

			// Check if the application knows about this call id
			if (itDialog != null && callID != null && !activeCallIDs.contains(callID)) {
				// Application doesn't know anything about this dialog...
				if (itDialog.auditTag == 0) {
					// Mark this dialog as suspect
					itDialog.auditTag = currentTime;
				} else {
					// We already audited this dialog before. Check if his
					// time's up.
					if (currentTime - itDialog.auditTag >= leakedDialogTimer) {
						// Leaked dialog found
						leakedDialogs++;

						// Generate report
						DialogState dialogState = itDialog.getState();
						String dialogReport = "dialog id: " + itDialog.getDialogId() + ", dialog state: "
								+ (dialogState != null ? dialogState.toString() : "null");
						auditReport += "    " + dialogReport + "\n";

						// Kill it
						itDialog.setState(SIPDialog.TERMINATED_STATE);
						if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
							logger.logDebug("auditDialogs: leaked " + dialogReport);
					}
				}
			}
		}

		// Return final report
		if (leakedDialogs > 0) {
			auditReport += "    Total: " + Integer.toString(leakedDialogs) + " leaked dialogs detected and removed.\n";
		} else {
			auditReport = null;
		}
		return auditReport;
	}

	/**
	 * Audits SIP transactions for leaks
	 *
	 * @return Audit report, null if no transaction leaks were found
	 */
	private String auditTransactions(ConcurrentHashMap<String, ? extends SIPTransaction> transactionsMap,
			long a_nLeakedTransactionTimer) {
		String auditReport = "  Leaked transactions:\n";
		int leakedTransactions = 0;
		long currentTime = System.currentTimeMillis();

		// Make a shallow copy of the transaction list.
		// This copy will remain intact as leaked transactions are removed by
		// the stack.
		LinkedList<SIPTransaction> transactionsList = new LinkedList<SIPTransaction>(transactionsMap.values());

		// Iterate through our copy
		Iterator<SIPTransaction> it = transactionsList.iterator();
		while (it.hasNext()) {
			SIPTransaction sipTransaction = (SIPTransaction) it.next();
			if (sipTransaction != null) {
				if (sipTransaction.getAuditTag() == 0) {
					// First time we see this transaction. Mark it as audited.
					sipTransaction.setAuditTag(currentTime);
				} else {
					// We've seen this transaction before. Check if his time's
					// up.
					if (currentTime - sipTransaction.getAuditTag() >= a_nLeakedTransactionTimer) {
						// Leaked transaction found
						leakedTransactions++;

						// Generate some report
						TransactionState transactionState = sipTransaction.getState();
						SIPRequest origRequest = sipTransaction.getOriginalRequest();
						String origRequestMethod = (origRequest != null ? origRequest.getMethod() : null);
						String transactionReport = sipTransaction.getClass().getName() + ", state: "
								+ (transactionState != null ? transactionState.toString() : "null") + ", OR: "
								+ (origRequestMethod != null ? origRequestMethod : "null");
						auditReport += "    " + transactionReport + "\n";

						// Kill it
						removeTransaction(sipTransaction);
						if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
							logger.logDebug("auditTransactions: leaked " + transactionReport);
					}
				}
			}
		}

		// Return final report
		if (leakedTransactions > 0) {
			auditReport += "    Total: " + Integer.toString(leakedTransactions)
					+ " leaked transactions detected and removed.\n";
		} else {
			auditReport = null;
		}
		return auditReport;
	}

	public void setNon2XXAckPassedToListener(boolean passToListener) {
		this.non2XXAckPassedToListener = passToListener;
	}

	/**
	 * @return the non2XXAckPassedToListener
	 */
	public boolean isNon2XXAckPassedToListener() {
		return non2XXAckPassedToListener;
	}

	/**
	 * Get the count of client transactions that is not in the completed or
	 * terminated state.
	 *
	 * @return the activeClientTransactionCount
	 */
	public int getActiveClientTransactionCount() {
		return activeClientTransactionCount.get();
	}

	public boolean isCancelClientTransactionChecked() {
		return this.cancelClientTransactionChecked;
	}

	public boolean isRemoteTagReassignmentAllowed() {
		return this.remoteTagReassignmentAllowed;
	}

	/**
	 * This method is slated for addition to the next spec revision.
	 *
	 *
	 * @return -- the collection of dialogs that is being managed by the stack.
	 */
	public Collection<Dialog> getDialogs() {
		HashSet<Dialog> dialogs = new HashSet<Dialog>();
		dialogs.addAll(this.dialogTable.values());
		dialogs.addAll(this.earlyDialogTable.values());
		return dialogs;
	}

	/**
	 *
	 * @return -- the collection of dialogs matching the state that is being managed
	 *         by the stack.
	 */
	public Collection<Dialog> getDialogs(DialogState state) {
		HashSet<Dialog> matchingDialogs = new HashSet<Dialog>();
		if (DialogState.EARLY.equals(state)) {
			matchingDialogs.addAll(this.earlyDialogTable.values());
		} else {
			Collection<SIPDialog> dialogs = dialogTable.values();
			for (SIPDialog dialog : dialogs) {
				if (dialog.getState() != null && dialog.getState().equals(state)) {
					matchingDialogs.add(dialog);
				}
			}
		}
		return matchingDialogs;
	}

	/**
	 * Get the Replaced Dialog from the stack.
	 *
	 * @param replacesHeader -- the header that references the dialog being
	 *                       replaced.
	 */
	public Dialog getReplacesDialog(ReplacesHeader replacesHeader) {
		String cid = replacesHeader.getCallId();
		String fromTag = replacesHeader.getFromTag();
		String toTag = replacesHeader.getToTag();

		for (SIPDialog dialog : this.dialogTable.values()) {
			if (dialog.getCallId().getCallId().equals(cid) && fromTag.equalsIgnoreCase(dialog.lastResponseFromTag)
					&& toTag.equalsIgnoreCase(dialog.lastResponseToTag)) {
				return dialog;
			}
		}

		StringBuilder dialogId = new StringBuilder(cid);

		// retval.append(COLON).append(to.getUserAtHostPort());
		if (toTag != null) {
			dialogId.append(":");
			dialogId.append(toTag);
		}
		// retval.append(COLON).append(from.getUserAtHostPort());
		if (fromTag != null) {
			dialogId.append(":");
			dialogId.append(fromTag);
		}
		String did = dialogId.toString().toLowerCase();
		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG))
			logger.logDebug("Looking for dialog " + did);
		/*
		 * Check if we can find this dialog in our dialog table.
		 */
		Dialog replacesDialog = getDialog(did);
		/*
		 * This could be a forked dialog. Search for it.
		 */
		if (replacesDialog == null) {
			for (SIPClientTransaction ctx : getAllClientTransactions()) {
				if (ctx.getDialog(did) != null) {
					replacesDialog = ctx.getDialog(did);
					break;
				}
			}
		}

		return replacesDialog;
	}

	/**
	 * Get the Join Dialog from the stack.
	 *
	 * @param joinHeader -- the header that references the dialog being joined.
	 */
	public Dialog getJoinDialog(JoinHeader joinHeader) {
		String cid = joinHeader.getCallId();
		String fromTag = joinHeader.getFromTag();
		String toTag = joinHeader.getToTag();

		StringBuilder retval = new StringBuilder(cid);

		// retval.append(COLON).append(to.getUserAtHostPort());
		if (toTag != null) {
			retval.append(":");
			retval.append(toTag);
		}
		// retval.append(COLON).append(from.getUserAtHostPort());
		if (fromTag != null) {
			retval.append(":");
			retval.append(fromTag);
		}
		return getDialog(retval.toString().toLowerCase());
	}

	/**
	 * @param timer the timer to set
	 */
	public void setTimer(SipTimer timer) {
		this.timer = timer;
	}

	/**
	 * @return the timer
	 */
	public SipTimer getTimer() throws IllegalStateException {
//        if(timer == null)
//            throw new IllegalStateException("Stack has been stopped, no further tasks can be scheduled.");
		return timer;
	}

	/**
	 * Size of the receive UDP buffer. This property affects performance under load.
	 * Bigger buffer is better under load.
	 *
	 * @return
	 */
	public int getReceiveUdpBufferSize() {
		return receiveUdpBufferSize;
	}

	/**
	 * Size of the receive UDP buffer. This property affects performance under load.
	 * Bigger buffer is better under load.
	 *
	 */
	public void setReceiveUdpBufferSize(int receiveUdpBufferSize) {
		this.receiveUdpBufferSize = receiveUdpBufferSize;
	}

	/**
	 * Size of the send UDP buffer. This property affects performance under load.
	 * Bigger buffer is better under load.
	 *
	 * @return
	 */
	public int getSendUdpBufferSize() {
		return sendUdpBufferSize;
	}

	public int getTcpSoRcvbuf() {
		return tcpSoRcvbuf;
	}

	public int getTcpSoSndbuf() {
		return tcpSoSndbuf;
	}

	/**
	 * Size of the send UDP buffer. This property affects performance under load.
	 * Bigger buffer is better under load.
	 *
	 */
	public void setSendUdpBufferSize(int sendUdpBufferSize) {
		this.sendUdpBufferSize = sendUdpBufferSize;
	}

	/**
	 * Flag that reqests checking of branch IDs on responses.
	 *
	 * @return
	 */
	public boolean checkBranchId() {
		return this.checkBranchId;
	}

	/**
	 * @param logStackTraceOnMessageSend the logStackTraceOnMessageSend to set
	 */
	public void setLogStackTraceOnMessageSend(boolean logStackTraceOnMessageSend) {
		this.logStackTraceOnMessageSend = logStackTraceOnMessageSend;
	}

	/**
	 * @return the logStackTraceOnMessageSend
	 */
	public boolean isLogStackTraceOnMessageSend() {
		return logStackTraceOnMessageSend;
	}

	public void setDeliverDialogTerminatedEventForNullDialog() {
		this.isDialogTerminatedEventDeliveredForNullDialog = true;
	}

	// public void addForkedClientTransaction(
	// SIPClientTransaction clientTransaction) {
	// String forkId = ((SIPRequest)clientTransaction.getRequest()).getForkId();
	// clientTransaction.setForkId(forkId);
	// if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
	// logger.logStackTrace();
	// logger.logDebug(
	// "Adding forked client transaction : " + clientTransaction + " branch=" +
	// clientTransaction.getBranch() +
	// " forkId = " + forkId + " sipDialog = " +
	// clientTransaction.getDefaultDialog() +
	// " sipDialogId= " + clientTransaction.getDefaultDialog().getDialogId());
	// logger.logDebug(String.format("addForkedClientTransaction: Table size : " +
	// " clientTransactionTable %d " +
	// " serverTransactionTable %d " +
	// " mergetTable %d " +
	// " terminatedServerTransactionsPendingAck %d " +
	// " forkedClientTransactionTable %d " +
	// " pendingTransactions %d " ,
	// clientTransactionTable.size(),
	// serverTransactionTable.size(),
	// mergeTable.size(),
	// terminatedServerTransactionsPendingAck.size(),
	// forkedClientTransactionTable.size(),
	// pendingTransactions.size()
	// ));
	// }

	// this.forkedClientTransactionTable.put(forkId, clientTransaction);
	// }

	// public void removeForkedClientTransaction(String forkId) {
	// if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
	// logger.logDebug("Removing forked client transaction : " + forkId);
	// }
	// SIPClientTransaction transaction =
	// this.forkedClientTransactionTable.remove(forkId);
	// if(transaction != null) {
	// if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
	// logger.logDebug(
	// "Removing forked client transaction: " + transaction +
	// ", branchId=" + transaction.getBranchId() +
	// ", forkId = " + forkId);
	// }
	// }
	// }

	public SIPClientTransaction getForkedTransaction(String forkId, String branchId) {
		if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
			logger.logDebug("Trying to find forked Transaction for forkId " + forkId + " branchId " + branchId);
		}
		SIPClientTransaction sipClientTransaction = (SIPClientTransaction) findTransaction(branchId.trim(), false);
		if (sipClientTransaction == null) {
			if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
				logger.logDebug("Didn't Found Forked Transaction for branchID " + branchId + " clientTransactionTable "
						+ clientTransactionTable);
			}
			return null;
		} else {
			if (sipClientTransaction.getForkId().equalsIgnoreCase(forkId)) {
				if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
					logger.logDebug("Found forked Transaction " + sipClientTransaction + " branchID "
							+ sipClientTransaction.getBranchId() + " for forkId " + forkId);
				}
				return sipClientTransaction;
			} else {
				if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
					logger.logDebug("Found Transaction for branchId " + branchId + " but didn't match forkId " + forkId
							+ " transactionForkId:" + sipClientTransaction.getForkId());
				}
				return null;
			}
		}
		// replaced with above code
		// SIPClientTransaction sipClientTransaction =
		// this.forkedClientTransactionTable.get(forkId);
		// if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
		// if(sipClientTransaction != null) {
		// logger.logDebug("Found forked Transaction " + sipClientTransaction + "
		// branchID " + sipClientTransaction.getBranchId() + " for forkId " + forkId);
		// } else {
		// logger.logDebug("Didn't Found Forked Transaction for forkId " + forkId);
		// }
		// }
		// return sipClientTransaction;
	}

	/**
	 * @param deliverUnsolicitedNotify the deliverUnsolicitedNotify to set
	 */
	public void setDeliverUnsolicitedNotify(boolean deliverUnsolicitedNotify) {
		this.deliverUnsolicitedNotify = deliverUnsolicitedNotify;
	}

	/**
	 * @return the deliverUnsolicitedNotify
	 */
	public boolean isDeliverUnsolicitedNotify() {
		return deliverUnsolicitedNotify;
	}

	/**
	 * @param deliverTerminatedEventForAck the deliverTerminatedEventForAck to set
	 */
	public void setDeliverTerminatedEventForAck(boolean deliverTerminatedEventForAck) {
		this.deliverTerminatedEventForAck = deliverTerminatedEventForAck;
	}

	/**
	 * @return the deliverTerminatedEventForAck
	 */
	public boolean isDeliverTerminatedEventForAck() {
		return deliverTerminatedEventForAck;
	}

	public long getMinKeepAliveInterval() {
		return this.minKeepAliveInterval;
	}

	public void setPatchWebSocketHeaders(Boolean patchWebSocketHeaders) {
		this.patchWebSocketHeaders = patchWebSocketHeaders;
	}

	public boolean isPatchWebSocketHeaders() {
		return patchWebSocketHeaders;
	}

	public void setPatchRport(Boolean patchRport) {
		this.patchRport = patchRport;
	}

	public boolean isPatchRport() {
		return patchRport;
	}

	public void setPatchReceivedRport(boolean patchReceivedRport) {
		this.patchReceivedRport = patchReceivedRport;
	}

	public boolean isPatchReceivedRport() {
		return patchReceivedRport;
	}

	/**
	 * @param maxForkTime the maxForkTime to set
	 */
	public void setMaxForkTime(int maxForkTime) {
		this.maxForkTime = maxForkTime;
	}

	/**
	 * @return the maxForkTime
	 */
	public int getMaxForkTime() {
		return maxForkTime;
	}

	/**
	 * This is a testing interface. Normally the application does not see
	 * retransmitted ACK for 200 OK retransmissions.
	 *
	 * @return
	 */
	public boolean isDeliverRetransmittedAckToListener() {
		return this.deliverRetransmittedAckToListener;
	}

	/**
	 * Get the dialog timeout counter.
	 *
	 * @return
	 */

	public int getAckTimeoutFactor() {
		if (getSipListener() != null && getSipListener() instanceof SipListenerExt) {
			return dialogTimeoutFactor;
		} else {
			return 64;
		}
	}

	public abstract SipListener getSipListener();

	/**
	 * @param messageParserFactory the messageParserFactory to set
	 */
	public void setMessageParserFactory(MessageParserFactory messageParserFactory) {
		this.messageParserFactory = messageParserFactory;
	}

	/**
	 * @return the messageParserFactory
	 */
	public MessageParserFactory getMessageParserFactory() {
		return messageParserFactory;
	}

	/**
	 * @param messageProcessorFactory the messageProcessorFactory to set
	 */
	public void setMessageProcessorFactory(MessageProcessorFactory messageProcessorFactory) {
		this.messageProcessorFactory = messageProcessorFactory;
	}

	/**
	 * @return the messageProcessorFactory
	 */
	public MessageProcessorFactory getMessageProcessorFactory() {
		return messageProcessorFactory;
	}

	/**
	 * @param aggressiveCleanup the aggressiveCleanup to set
	 */
	public void setAggressiveCleanup(boolean aggressiveCleanup) {
		if (aggressiveCleanup)
			releaseReferencesStrategy = ReleaseReferencesStrategy.Normal;
		else
			releaseReferencesStrategy = ReleaseReferencesStrategy.None;
	}

	/**
	 * @return the aggressiveCleanup
	 */
	public boolean isAggressiveCleanup() {
		if (releaseReferencesStrategy == ReleaseReferencesStrategy.None)
			return false;
		else
			return true;
	}

	public int getEarlyDialogTimeout() {
		return this.earlyDialogTimeout;
	}

	/**
	 * @param clientAuth the clientAuth to set
	 */
	public void setClientAuth(ClientAuthType clientAuth) {
		this.clientAuth = clientAuth;
	}

	/**
	 * @return the clientAuth
	 */
	public ClientAuthType getClientAuth() {
		return clientAuth;
	}

	/**
	 * @param threadPriority the threadPriority to set
	 */
	public void setThreadPriority(int threadPriority) {
		if (threadPriority < Thread.MIN_PRIORITY)
			throw new IllegalArgumentException("The Stack Thread Priority shouldn't be lower than Thread.MIN_PRIORITY");
		if (threadPriority > Thread.MAX_PRIORITY)
			throw new IllegalArgumentException(
					"The Stack Thread Priority shouldn't be higher than Thread.MAX_PRIORITY");
		if (logger.isLoggingEnabled(StackLogger.TRACE_INFO)) {
			logger.logInfo("Setting Stack Thread priority to " + threadPriority);
		}
		this.threadPriority = threadPriority;
	}

	/**
	 * @return the threadPriority
	 */
	public int getThreadPriority() {
		return threadPriority;
	}

	public int getReliableConnectionKeepAliveTimeout() {
		return reliableConnectionKeepAliveTimeout;
	}

	public void setReliableConnectionKeepAliveTimeout(int reliableConnectionKeepAliveTimeout) {
//        if (reliableConnectionKeepAliveTimeout < 0){
//            throw new IllegalArgumentException("The Stack reliableConnectionKeepAliveTimeout can not be negative. reliableConnectionKeepAliveTimeoutCandidate = " + reliableConnectionKeepAliveTimeout);
//
//        } else 
		if (reliableConnectionKeepAliveTimeout == 0) {

			if (logger.isLoggingEnabled(LogWriter.TRACE_INFO)) {
				logger.logInfo(
						"Default value (840000 ms) will be used for reliableConnectionKeepAliveTimeout stack property");
			}
			reliableConnectionKeepAliveTimeout = 840000;
		}
		if (logger.isLoggingEnabled(LogWriter.TRACE_INFO)) {
			logger.logInfo("value " + reliableConnectionKeepAliveTimeout
					+ " will be used for reliableConnectionKeepAliveTimeout stack property");
		}
		this.reliableConnectionKeepAliveTimeout = reliableConnectionKeepAliveTimeout;
	}

	public MessageProcessor findMessageProcessor(String address, int port, String transport) {
		String key = address.concat(":").concat("" + port).concat("/").concat(transport).toLowerCase();
		return messageProcessors.get(key);
	}

	/**
	 * Route the messageToSend internally without going through the network using an
	 * executor
	 * 
	 * @param channel       channel to use to route the message internally
	 * @param messageToSend message to send
	 */
	public void selfRouteMessage(RawMessageChannel channel, SIPMessage messageToSend) {
		if (logger.isLoggingEnabled(LogLevels.TRACE_DEBUG)) {
			logger.logDebug("Self routing message " + channel.getTransport());
		}
		IncomingMessageProcessingTask processMessageTask = new IncomingMessageProcessingTask(channel,
				(SIPMessage) messageToSend.clone());
		messageProcessorExecutor.addTaskLast(processMessageTask);
	}

	/**
	 * Find suitable MessageProcessor and calls it's
	 * {@link ConnectionOrientedMessageProcessor#setKeepAliveTimeout(String, int, long)}
	 * method passing peerAddress and peerPort as arguments.
	 *
	 * @param myAddress   - server ip address
	 * @param myPort      - server port
	 * @param transport   - transport
	 * @param peerAddress - peerAddress
	 * @param peerPort    - peerPort
	 * @return result of invocation of
	 *         {@link ConnectionOrientedMessageProcessor#setKeepAliveTimeout(String, int, long)}
	 *         if MessageProcessor was found
	 */
	public boolean setKeepAliveTimeout(String myAddress, int myPort, String transport, String peerAddress, int peerPort,
			long keepAliveTimeout) {

		MessageProcessor processor = findMessageProcessor(myAddress, myPort, transport);

		if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
			logger.logDebug("~~~ Trying to find MessageChannel and set new KeepAliveTimeout( myAddress=" + myAddress
					+ ", myPort=" + myPort + ", transport=" + transport + ", peerAddress=" + peerAddress + ", peerPort="
					+ peerPort + ", keepAliveTimeout=" + keepAliveTimeout + "), MessageProcessor=" + processor);
		}

		if (processor == null) {
			return false;
		}

		if (processor instanceof ConnectionOrientedMessageProcessor)
			return ((ConnectionOrientedMessageProcessor) processor).setKeepAliveTimeout(peerAddress, peerPort,
					keepAliveTimeout);
		if (processor instanceof NettyStreamMessageProcessor)
			return ((NettyStreamMessageProcessor) processor).setKeepAliveTimeout(peerAddress, peerPort,
					keepAliveTimeout);
		else
			return false;
	}

	/**
	 * Find suitable MessageProcessor and calls it's
	 * {@link ConnectionOrientedMessageProcessor#closeReliableConnection(String, int)}
	 * method passing peerAddress and peerPort as arguments.
	 *
	 * @param myAddress   - server ip address
	 * @param myPort      - server port
	 * @param transport   - transport
	 * @param peerAddress - peerAddress
	 * @param peerPort    - peerPort
	 */
	public boolean closeReliableConnection(String myAddress, int myPort, String transport, String peerAddress,
			int peerPort) {

		MessageProcessor processor = findMessageProcessor(myAddress, myPort, transport);

		if (processor != null && processor instanceof ConnectionOrientedMessageProcessor) {
			if (logger.isLoggingEnabled(LogWriter.TRACE_DEBUG)) {
				logger.logDebug("~~~ closeReliableConnection( myAddress=" + myAddress + ", myPort=" + myPort
						+ ", transport=" + transport + ", peerAddress=" + peerAddress + ", peerPort=" + peerPort
						+ "), MessageProcessor=" + processor);
			}
			return ((ConnectionOrientedMessageProcessor) processor).closeReliableConnection(peerAddress, peerPort);
		}
		return false;
	}

	/**
	 * @return the sslHandshakeTimeout
	 */
	public long getSslHandshakeTimeout() {
		return sslHandshakeTimeout;
	}

	/**
	 * @param sslHandshakeTimeout the sslHandshakeTimeout to set
	 */
	public void setSslHandshakeTimeout(long sslHandshakeTimeout) {
		this.sslHandshakeTimeout = sslHandshakeTimeout;
	}

	/**
	 * @param earlyDialogTimeout the earlyDialogTimeout to set
	 */
	public void setEarlyDialogTimeout(int earlyDialogTimeout) {
		this.earlyDialogTimeout = earlyDialogTimeout;
	}

	/**
	 * @return the maxTxLifetimeInvite
	 */
	public int getMaxTxLifetimeInvite() {
		return maxTxLifetimeInvite;
	}

	/**
	 * @param maxTxLifetimeInvite the maxTxLifetimeInvite to set
	 */
	public void setMaxTxLifetimeInvite(int maxTxLifetimeInvite) {
		this.maxTxLifetimeInvite = maxTxLifetimeInvite;
	}

	/**
	 * @return the maxTxLifetimeNonInvite
	 */
	public int getMaxTxLifetimeNonInvite() {
		return maxTxLifetimeNonInvite;
	}

	/**
	 * @param maxTxLifetimeNonInvite the maxTxLifetimeNonInvite to set
	 */
	public void setMaxTxLifetimeNonInvite(int maxTxLifetimeNonInvite) {
		this.maxTxLifetimeNonInvite = maxTxLifetimeNonInvite;
	}

	public boolean isAllowDialogOnDifferentProvider() {
		return allowDialogOnDifferentProvider;
	}

	public void setAllowDialogOnDifferentProvider(boolean allowDialogOnDifferentProvider) {
		this.allowDialogOnDifferentProvider = allowDialogOnDifferentProvider;
	}

	public boolean isSslRenegotiationEnabled() {
		return sslRenegotiationEnabled;
	}

	public void setSslRenegotiationEnabled(boolean sslRenegotiationEnabled) {
		this.sslRenegotiationEnabled = sslRenegotiationEnabled;
	}

	/**
	 * @return the connectionLingerTimer
	 */
	public int getConnectionLingerTimer() {
		return connectionLingerTimer;
	}

	/**
	 * @param connectionLingerTimer the connectionLingerTimer to set
	 */
	public void setConnectionLingerTimer(int connectionLingerTimer) {
		SIPTransactionStack.connectionLingerTimer = connectionLingerTimer;
	}

	/**
	 * @return the stackCongestionControlTimeout
	 */
	public int getStackCongestionControlTimeout() {
		return stackCongestionControlTimeout;
	}

	/**
	 * @param stackCongestionControlTimeout the stackCongestionControlTimeout to set
	 */
	public void setStackCongestionControlTimeout(int stackCongestionControlTimeout) {
		this.stackCongestionControlTimeout = stackCongestionControlTimeout;
	}

	/**
	 * @return the releaseReferencesStrategy
	 */
	public ReleaseReferencesStrategy getReleaseReferencesStrategy() {
		return releaseReferencesStrategy;
	}

	/**
	 * @param releaseReferencesStrategy the releaseReferencesStrategy to set
	 */
	public void setReleaseReferencesStrategy(ReleaseReferencesStrategy releaseReferencesStrategy) {
		this.releaseReferencesStrategy = releaseReferencesStrategy;
	}

	/*
	 * 
	 */
	public int getThreadPoolSize() {
		return threadPoolSize;
	}

	public int getConnectionTimeout() {
		return connTimeout;
	}

	public int getReadTimeout() {
		return readTimeout;
	}

	public boolean isUdpFlag() {
		return udpFlag;
	}

	public void setUdpFlag(boolean udpFlag) {
		this.udpFlag = udpFlag;
	}

	public boolean isComputeContentLengthFromMessage() {
		return computeContentLengthFromMessage;
	}

	public SecurityManagerProvider getSecurityManagerProvider() {
		return securityManagerProvider;
	}

	public Boolean getSctpDisableFragments() {
		return sctpDisableFragments;
	}

	public Integer getSctpFragmentInterleave() {
		return sctpFragmentInterleave;
	}

	// public Integer getSctpInitMaxStreams() {
	// return sctpInitMaxStreams;
	// }

	public Boolean getSctpNodelay() {
		return sctpNoDelay;
	}

	public Integer getSctpSoSndbuf() {
		return sctpSoSndbuf;
	}

	public Integer getSctpSoRcvbuf() {
		return sctpSoRcvbuf;
	}

	public Integer getSctpSoLinger() {
		return sctpSoLinger;
	}
}
