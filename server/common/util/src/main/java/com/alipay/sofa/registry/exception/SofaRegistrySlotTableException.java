/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.exception;

/**
 * @author chen.zhu
 *     <p>Jan 26, 2021
 */
public class SofaRegistrySlotTableException extends SofaRegistryRuntimeException {
  public SofaRegistrySlotTableException(String message) {
    super(message);
  }

  public SofaRegistrySlotTableException(String message, Throwable cause) {
    super(message, cause);
  }

  public SofaRegistrySlotTableException(Throwable cause) {
    super(cause);
  }

  public SofaRegistrySlotTableException(
      String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
