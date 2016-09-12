/**
 * 
 */
package com.weibo.dip.flume.extension.test;

import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.CharEncoding;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.flume.Context;
import org.apache.flume.event.EventBuilder;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.weibo.dip.flume.extension.sink.scribe.EventToLogEntrySerializer;
import com.weibo.dip.flume.extension.sink.scribe.FlumeEventSerializer;
import com.weibo.dip.flume.extension.sink.scribe.Scribe;
import com.weibo.dip.flume.extension.sink.scribe.ScribeSinkConfigurationConstants;

/**
 * @author yurun
 *
 */
public class MultithreadingScribeClientMain {

	private static final Logger LOGGER = LoggerFactory.getLogger(MultithreadingScribeClientMain.class);

	private static final AtomicLong COUNTING = new AtomicLong(0);

	private static class ScribeLogger implements Runnable {

		private String host;

		private int port;

		private String category;

		private long lines;

		public ScribeLogger(String host, int port, String category, long lines) {
			this.host = host;

			this.port = port;

			this.category = category;

			this.lines = lines;
		}

		@Override
		public void run() {
			TTransport transport = null;

			Scribe.Client client = null;

			try {
				transport = new TFramedTransport(new TSocket(new Socket(host, port)));

				client = new Scribe.Client(new TBinaryProtocol(transport, false, false));

				SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");

				FlumeEventSerializer serializer = new EventToLogEntrySerializer();

				Map<String, String> parameters = new HashMap<>();

				parameters.put(ScribeSinkConfigurationConstants.CONFIG_SCRIBE_CATEGORY_HEADER, "category");

				serializer.configure(new Context(parameters));

				long count = 0;

				while (count <= lines) {
					String line = sdf.format(new Date(System.currentTimeMillis())) + "_"
							+ Thread.currentThread().getName() + "_" + UUID.randomUUID().toString();

					Map<String, String> headers = new HashMap<>();

					headers.put("category", category);

					client.Log(Arrays.asList(
							serializer.serialize(EventBuilder.withBody(line.getBytes(CharEncoding.UTF_8), headers))));

					count++;

					COUNTING.incrementAndGet();
				}
			} catch (Exception e) {
				LOGGER.error("ScribeLogger" + Thread.currentThread().getName() + " log error: "
						+ ExceptionUtils.getFullStackTrace(e));
			} finally {
				client = null;

				if (transport != null) {
					transport.close();
				}
			}
		}

	}

	private static class Monitor extends Thread {

		@Override
		public void run() {
			while (true) {
				try {
					Thread.sleep(5 * 1000);
				} catch (InterruptedException e) {
				}

				LOGGER.info("ScribeClient log " + COUNTING.get() + " lines");
			}
		}

	}

	public static void main(String[] args) {
		Options scribeClientOptions = new Options();

		scribeClientOptions
				.addOption(Option.builder("host").hasArg().argName("scribe(flume) server host").required().build());
		scribeClientOptions
				.addOption(Option.builder("port").hasArg().argName("scribe(flume) server port").required().build());
		scribeClientOptions.addOption(Option.builder("category").hasArg().argName("category name").required().build());
		scribeClientOptions
				.addOption(Option.builder("threads").hasArg().argName("thread number").required(false).build());
		scribeClientOptions.addOption(
				Option.builder("lines").hasArg().argName("every thread will log lines").required(false).build());
		scribeClientOptions.addOption(Option.builder("help").hasArg(false).required(false).build());

		HelpFormatter formatter = new HelpFormatter();

		if (ArrayUtils.isEmpty(args)) {
			formatter.printHelp("Scribe Client COMMAND", scribeClientOptions);

			return;
		}

		CommandLineParser parser = new DefaultParser();

		CommandLine commandLine = null;

		try {
			commandLine = parser.parse(scribeClientOptions, args);
		} catch (ParseException e) {
			System.out.println("Error: " + e.getMessage());

			formatter.printHelp("Scribe Client COMMAND", scribeClientOptions);

			return;
		}

		String host = commandLine.getOptionValue("host");

		int port = Integer.valueOf(commandLine.getOptionValue("port"));

		String category = commandLine.getOptionValue("category");

		int threads = 1;
		if (commandLine.hasOption("threads")) {
			threads = Integer.valueOf(commandLine.getOptionValue("threads"));
		}

		long lines = Long.MAX_VALUE;
		if (commandLine.hasOption("lines")) {
			lines = Long.valueOf(commandLine.getOptionValue("lines"));
		}

		Monitor monitor = new Monitor();

		monitor.setDaemon(true);

		monitor.start();

		ExecutorService loggers = Executors.newFixedThreadPool(threads);

		for (int index = 0; index < threads; index++) {
			loggers.submit(new ScribeLogger(host, port, category, lines));
		}

		loggers.shutdown();

		while (!loggers.isTerminated()) {
			try {
				loggers.awaitTermination(1, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
			}
		}

		LOGGER.info("ScribeClient total log " + COUNTING.get() + " lines");
	}

}