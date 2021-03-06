/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.distributedlog.messaging;

import static com.google.common.base.Charsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.distributedlog.api.AsyncLogReader;
import org.apache.distributedlog.api.AsyncLogWriter;
import org.apache.distributedlog.DLSN;
import org.apache.distributedlog.DistributedLogConfiguration;
import org.apache.distributedlog.api.DistributedLogManager;
import org.apache.distributedlog.LogRecord;
import org.apache.distributedlog.LogRecordWithDLSN;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.distributedlog.exceptions.LogEmptyException;
import org.apache.distributedlog.exceptions.LogNotFoundException;
import org.apache.distributedlog.api.namespace.NamespaceBuilder;
import org.apache.distributedlog.thrift.messaging.TransformedRecord;
import org.apache.distributedlog.common.concurrent.FutureEventListener;
import org.apache.distributedlog.common.concurrent.FutureUtils;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TIOStreamTransport;

/**
 * Transform one stream to another stream. And apply transformation
 */
public class StreamTransformer {

    private final static String HELP = "StreamTransformer <uri> <src_stream> <target_stream>";
    private final static TProtocolFactory protocolFactory =
            new TBinaryProtocol.Factory();

    public static void main(String[] args) throws Exception {
        if (3 != args.length) {
            System.out.println(HELP);
            return;
        }

        String dlUriStr = args[0];
        final String srcStreamName = args[1];
        final String targetStreamName = args[2];

        URI uri = URI.create(dlUriStr);
        DistributedLogConfiguration conf = new DistributedLogConfiguration();
        conf.setOutputBufferSize(16*1024); // 16KB
        conf.setPeriodicFlushFrequencyMilliSeconds(5); // 5ms
        Namespace namespace = NamespaceBuilder.newBuilder()
                .conf(conf)
                .uri(uri)
                .build();

        // open the dlm
        System.out.println("Opening log stream " + srcStreamName);
        DistributedLogManager srcDlm = namespace.openLog(srcStreamName);
        System.out.println("Opening log stream " + targetStreamName);
        DistributedLogManager targetDlm = namespace.openLog(targetStreamName);

        Transformer<byte[], byte[]> replicationTransformer =
                new IdenticalTransformer<byte[]>();

        LogRecordWithDLSN lastTargetRecord;
        DLSN srcDlsn;
        try {
            lastTargetRecord = targetDlm.getLastLogRecord();
            TransformedRecord lastTransformedRecord = new TransformedRecord();
            try {
                lastTransformedRecord.read(protocolFactory.getProtocol(
                        new TIOStreamTransport(new ByteArrayInputStream(lastTargetRecord.getPayload()))));
                srcDlsn = DLSN.deserializeBytes(lastTransformedRecord.getSrcDlsn());
                System.out.println("Last transformed record is " + srcDlsn);
            } catch (TException e) {
                System.err.println("Error on reading last transformed record");
                e.printStackTrace(System.err);
                srcDlsn = DLSN.InitialDLSN;
            }
        } catch (LogNotFoundException lnfe) {
            srcDlsn = DLSN.InitialDLSN;
        } catch (LogEmptyException lee) {
            srcDlsn = DLSN.InitialDLSN;
        }

        AsyncLogWriter targetWriter = FutureUtils.result(targetDlm.openAsyncLogWriter());
        try {
            readLoop(srcDlm, srcDlsn, targetWriter, replicationTransformer);
        } finally {
            FutureUtils.result(targetWriter.asyncClose(), 5, TimeUnit.SECONDS);
            targetDlm.close();
            srcDlm.close();
            namespace.close();
        }

    }

    private static void readLoop(final DistributedLogManager dlm,
                                 final DLSN fromDLSN,
                                 final AsyncLogWriter targetWriter,
                                 final Transformer<byte[], byte[]> replicationTransformer)
            throws Exception {

        final CountDownLatch keepAliveLatch = new CountDownLatch(1);

        System.out.println("Wait for records starting from " + fromDLSN);
        final AsyncLogReader reader = FutureUtils.result(dlm.openAsyncLogReader(fromDLSN));
        final FutureEventListener<LogRecordWithDLSN> readListener = new FutureEventListener<LogRecordWithDLSN>() {
            @Override
            public void onFailure(Throwable cause) {
                System.err.println("Encountered error on reading records from stream " + dlm.getStreamName());
                cause.printStackTrace(System.err);
                keepAliveLatch.countDown();
            }

            @Override
            public void onSuccess(LogRecordWithDLSN record) {
                if (record.getDlsn().compareTo(fromDLSN) <= 0) {
                    reader.readNext().whenComplete(this);
                    return;
                }
                System.out.println("Received record " + record.getDlsn());
                System.out.println("\"\"\"");
                System.out.println(new String(record.getPayload(), UTF_8));
                System.out.println("\"\"\"");
                try {
                    transform(targetWriter, record, replicationTransformer, keepAliveLatch);
                } catch (Exception e) {
                    System.err.println("Encountered error on transforming record " + record.getDlsn()
                            + " from stream " + dlm.getStreamName());
                    e.printStackTrace(System.err);
                    keepAliveLatch.countDown();
                }
                reader.readNext().whenComplete(this);
            }
        };
        reader.readNext().whenComplete(readListener);

        keepAliveLatch.await();
        FutureUtils.result(reader.asyncClose(), 5, TimeUnit.SECONDS);
    }

    private static void transform(final AsyncLogWriter writer,
                                  LogRecordWithDLSN record,
                                  Transformer<byte[], byte[]> replicationTransformer,
                                  final CountDownLatch keepAliveLatch)
            throws Exception {
        DLSN srcDLSN = record.getDlsn();
        byte[] payload = record.getPayload();
        byte[] transformedPayload = replicationTransformer.transform(payload);
        TransformedRecord transformedRecord =
                new TransformedRecord(ByteBuffer.wrap(transformedPayload));
        transformedRecord.setSrcDlsn(srcDLSN.serializeBytes());
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        transformedRecord.write(protocolFactory.getProtocol(new TIOStreamTransport(baos)));
        byte[] data = baos.toByteArray();
        writer.write(new LogRecord(record.getSequenceId(), data))
                .whenComplete(new FutureEventListener<DLSN>() {
            @Override
            public void onFailure(Throwable cause) {
                System.err.println("Encountered error on writing records to stream " + writer.getStreamName());
                cause.printStackTrace(System.err);
                keepAliveLatch.countDown();
            }

            @Override
            public void onSuccess(DLSN dlsn) {
                System.out.println("Write transformed record " + dlsn);
            }
        });
    }

}
