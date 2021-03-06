/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2019 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.common.interceptors;

import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import feast.common.logging.AuditLogger;
import feast.common.logging.entry.MessageAuditLogEntry;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.event.Level;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * GrpcMessageInterceptor intercepts a GRPC calls to log handling of GRPC messages to the Audit Log.
 * Intercepts the incoming and outgoing messages logs them to the audit log, together with method
 * name and assumed authenticated identity (if authentication is enabled). NOTE:
 * GrpcMessageInterceptor assumes that all service calls are unary (ie single request/response).
 */
public class GrpcMessageInterceptor implements ServerInterceptor {
  @Override
  public <ReqT, RespT> Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    MessageAuditLogEntry.Builder entryBuilder = MessageAuditLogEntry.newBuilder();
    // default response message to empty proto in log entry.
    entryBuilder.setResponse(Empty.newBuilder().build());

    // Unpack service & method name from call
    // full method name is in format <classpath>.<Service>/<Method>
    String fullMethodName = call.getMethodDescriptor().getFullMethodName();
    entryBuilder.setService(
        fullMethodName.substring(fullMethodName.lastIndexOf(".") + 1, fullMethodName.indexOf("/")));
    entryBuilder.setMethod(fullMethodName.substring(fullMethodName.indexOf("/") + 1));

    // Attempt Extract current authenticated identity.
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String identity = (authentication == null) ? "" : authentication.getName();
    entryBuilder.setIdentity(identity);

    // Register forwarding call to intercept outgoing response and log to audit log
    call =
        new SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendMessage(RespT message) {
            // 2. Track the response & Log entry to audit logger
            super.sendMessage(message);
            entryBuilder.setResponse((Message) message);
          }

          @Override
          public void close(Status status, Metadata trailers) {
            super.close(status, trailers);
            // 3. Log the message log entry to the audit log
            Level logLevel = (status.isOk()) ? Level.INFO : Level.ERROR;
            entryBuilder.setStatusCode(status.getCode());
            AuditLogger.logMessage(logLevel, entryBuilder);
          }
        };

    ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
    return new SimpleForwardingServerCallListener<ReqT>(listener) {
      @Override
      // Register listener to intercept incoming request messages and log to audit log
      public void onMessage(ReqT message) {
        super.onMessage(message);
        // 1. Track the request.
        entryBuilder.setRequest((Message) message);
      }
    };
  }
}
