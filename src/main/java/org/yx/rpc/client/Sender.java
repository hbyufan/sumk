/**
 * Copyright (C) 2016 - 2030 youtongluan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.yx.rpc.client;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.mina.core.future.WriteFuture;
import org.yx.common.LogType;
import org.yx.conf.AppInfo;
import org.yx.exception.SoaException;
import org.yx.rpc.Host;
import org.yx.rpc.RpcCode;
import org.yx.rpc.client.route.HostChecker;
import org.yx.rpc.client.route.Routes;
import org.yx.rpc.client.route.RpcRoute;
import org.yx.util.Assert;
import org.yx.util.GsonUtil;

public final class Sender {

	private static enum ParamType {
		JSONARRAY, JSON
	}

	private final String api;
	private Object params;
	private ParamType paramType;
	private int totalTimeout;

	private long totalStart;

	private Host[] directUrls;

	private boolean backup;
	private static AtomicInteger counter = new AtomicInteger();
	private Consumer<RpcResult> callback;

	Sender(String api) {
		this.api = api;
	}

	public Sender directUrls(Host... urls) {
		this.directUrls = urls;
		return this;
	}

	public Sender backup(boolean backup) {
		this.backup = backup;
		return this;
	}

	public Sender totalTimeout(int timeout) {
		this.totalTimeout = timeout;
		return this;
	}

	public Sender callback(Consumer<RpcResult> callback) {
		this.callback = callback;
		return this;
	}

	public Sender paramInArray(Object... args) {
		String[] params = new String[args.length];
		for (int i = 0; i < args.length; i++) {
			params[i] = GsonUtil.toJson(args[i]);
		}
		this.params = params;
		this.paramType = ParamType.JSONARRAY;
		return this;
	}

	public Sender paramInJson(String json) {
		this.params = json;
		this.paramType = ParamType.JSON;
		return this;
	}

	public Sender paramInMap(Map<String, ?> map) {
		this.params = GsonUtil.toJson(map);
		this.paramType = ParamType.JSON;
		return this;
	}

	/**
	 * 本方法调用之后，不允许再调用本对象的任何方法<BR>
	 * 
	 * @return 用无论是否成功，都会返回future。如果失败的话，异常包含在future中。<BR>
	 *         通信异常是SoaException；如果是业务类异常，则是BizException
	 */
	public RpcFuture execute() {
		Assert.notEmpty(api, "api cannot be empty");
		Assert.notNull(this.paramType, "param have not been set");
		this.totalStart = System.currentTimeMillis();
		Req req = Rpc.createReq(this.api);
		if (this.paramType == ParamType.JSONARRAY) {
			req.setParamArray((String[]) this.params);
		} else {
			req.setJsonedParam((String) this.params);
		}
		if (this.totalTimeout < 1) {
			this.totalTimeout = AppInfo.getInt("soa.timeout", 30000);
		}
		RpcFuture f = sendAsync(req, this.totalStart + this.totalTimeout);
		if (f.getClass() == ErrorRpcFuture.class) {
			ErrorRpcFuture errorFuture = ErrorRpcFuture.class.cast(f);
			RpcLocker locker = errorFuture.locker;
			LockHolder.remove(locker.req.getSn());
			locker.wakeup(errorFuture.rpcResult());
		}
		return f;
	}

	private Host useDirectUrl() {
		int index = counter.incrementAndGet();
		if (index < 0) {
			counter.set((int) (System.nanoTime() & 0xff));
			index = counter.incrementAndGet();
		}
		for (int i = 0; i < this.directUrls.length; i++) {
			index %= directUrls.length;
			Host url = this.directUrls[index];
			if (!HostChecker.get().isDowned(url)) {
				return url;
			}
		}
		return null;
	}

	private RpcFuture sendAsync(Req req, long endTime) {
		String api = req.getApi();
		final RpcLocker locker = new RpcLocker(req, callback);
		Host url = null;
		if (this.directUrls != null && this.directUrls.length > 0) {
			url = useDirectUrl();
			if (url == null && !this.backup) {
				SoaException ex = new SoaException(RpcCode.NO_NODE_AVAILABLE,
						"all directUrls is disabled:" + Arrays.toString(this.directUrls), (String) null);
				return new ErrorRpcFuture(ex, locker);
			}
		}
		if (url == null) {
			RpcRoute route = Routes.getRoute(api);
			if (route == null) {
				SoaException ex = new SoaException(RpcCode.NO_ROUTE, "can not find route for " + api, (String) null);
				return new ErrorRpcFuture(ex, locker);
			}
			url = route.getUrl();
		}
		if (url == null) {
			SoaException ex = new SoaException(RpcCode.NO_NODE_AVAILABLE, "route for " + api + " are all disabled",
					(String) null);
			return new ErrorRpcFuture(ex, locker);
		}
		locker.url(url);
		WriteFuture f = null;
		try {
			ReqSession session = ReqSessionHolder.getSession(url);
			LockHolder.register(locker, endTime);
			f = session.write(req);
		} catch (Exception e) {
			LogType.RPC_LOG.error(e.toString(), e);
		}
		if (f == null) {
			SoaException ex = new SoaException(RpcCode.SEND_FAILED, url + " can not connect", (String) null);
			return new ErrorRpcFuture(ex, locker);
		}
		f.addListener(locker);
		return new RpcFutureImpl(locker);
	}

}
