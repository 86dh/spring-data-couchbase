/*
 * Copyright 2012-2021 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.couchbase;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.couchbase.transactions.TransactionContext;
import com.couchbase.transactions.config.MergedTransactionConfig;
import com.couchbase.transactions.config.PerTransactionConfig;
import com.couchbase.transactions.config.PerTransactionConfigBuilder;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.couchbase.core.CouchbaseExceptionTranslator;
import org.springframework.data.couchbase.transaction.ClientSession;
import org.springframework.data.couchbase.transaction.ClientSessionImpl;
import org.springframework.data.couchbase.transaction.ClientSessionOptions;
import org.springframework.data.couchbase.transaction.CouchbaseStuffHandle;

import com.couchbase.client.core.env.Authenticator;
import com.couchbase.client.core.env.OwnedSupplier;
import com.couchbase.client.core.io.CollectionIdentifier;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.transactions.AttemptContextReactive;
import com.couchbase.transactions.Transactions;
import com.couchbase.transactions.config.TransactionConfig;

/**
 * The default implementation of a {@link CouchbaseClientFactory}.
 *
 * @author Michael Nitschinger
 * @author Michael Reiche
 */
public class SimpleCouchbaseClientFactory implements CouchbaseClientFactory {

	private final Supplier<Cluster> cluster;
	private final Bucket bucket;
	private final Scope scope;
	private final PersistenceExceptionTranslator exceptionTranslator;
	private final CouchbaseStuffHandle transactionalOperator;

	public SimpleCouchbaseClientFactory(final String connectionString, final Authenticator authenticator,
			final String bucketName) {
		this(connectionString, authenticator, bucketName, null);
	}

	public SimpleCouchbaseClientFactory(final String connectionString, final Authenticator authenticator,
			final String bucketName, final String scopeName) {
		this(new OwnedSupplier<>(Cluster.connect(connectionString, ClusterOptions.clusterOptions(authenticator))),
				bucketName, scopeName);
	}

	public SimpleCouchbaseClientFactory(final String connectionString, final Authenticator authenticator,
			final String bucketName, final String scopeName, final ClusterEnvironment environment) {
		this(
				new OwnedSupplier<>(
						Cluster.connect(connectionString, ClusterOptions.clusterOptions(authenticator).environment(environment))),
				bucketName, scopeName);
	}

	public SimpleCouchbaseClientFactory(final Cluster cluster, final String bucketName, final String scopeName) {
		this(() -> cluster, bucketName, scopeName);
	}

	private SimpleCouchbaseClientFactory(final Supplier<Cluster> cluster, final String bucketName,
			final String scopeName) {
		this(cluster, bucketName, scopeName, null);
	}

	private SimpleCouchbaseClientFactory(final Supplier<Cluster> cluster, final String bucketName, final String scopeName,
			final CouchbaseStuffHandle transactionalOperator) {
		this.cluster = cluster;
		this.bucket = cluster.get().bucket(bucketName);
		this.scope = scopeName == null ? bucket.defaultScope() : bucket.scope(scopeName);
		this.exceptionTranslator = new CouchbaseExceptionTranslator();
		this.transactionalOperator = transactionalOperator;
	}

	@Override
	public CouchbaseClientFactory withScope(final String scopeName) {
		return new SimpleCouchbaseClientFactory(cluster, bucket.name(), scopeName != null ? scopeName : getScope().name());
	}

	@Override
	public Cluster getCluster() {
		return cluster.get();
	}

	@Override
	public Bucket getBucket() {
		return bucket;
	}

	@Override
	public Scope getScope() {
		return scope;
	}

	@Override
	public Collection getCollection(final String collectionName) {
		final Scope scope = getScope();
		if (collectionName == null || CollectionIdentifier.DEFAULT_COLLECTION.equals(collectionName)) {
			if (!scope.name().equals(CollectionIdentifier.DEFAULT_SCOPE)) {
				throw new IllegalStateException("A collectionName must be provided if a non-default scope is used");
			}
			return getBucket().defaultCollection();
		}
		return scope.collection(collectionName);
	}

	@Override
	public Collection getDefaultCollection() {
		return getCollection(null);
	}

	@Override
	public PersistenceExceptionTranslator getExceptionTranslator() {
		return exceptionTranslator;
	}

	@Override
	public ClientSession getSession(ClientSessionOptions options, Transactions transactions, TransactionConfig config,
			AttemptContextReactive atr) {

		AttemptContextReactive at = atr != null ? atr : transactions.reactive().newAttemptContextReactive();

		return new ClientSessionImpl(this, transactions, config, at);
	}

	/* copied from AttemptContextReactive - needs to have cleanup() and createAttemptContext() public

	  public AttemptContextReactive newAttemptContextReactive(Transactions transactions, TransactionConfig config){
		PerTransactionConfig perConfig = PerTransactionConfigBuilder.create().build();
		MergedTransactionConfig merged = new MergedTransactionConfig(config, Optional.of(perConfig));

		TransactionContext overall = new TransactionContext(
				transactions.reactive().cleanup().clusterData().cluster().environment().requestTracer(),
				transactions.reactive().cleanup().clusterData().cluster().environment().eventBus(),
				UUID.randomUUID().toString(), now(), Duration.ZERO, merged);

		String txnId = UUID.randomUUID().toString();
		//overall.LOGGER.info(configDebug(config, perConfig));
		return transactions.reactive().createAttemptContext(overall, merged, txnId);
	}
	*/

	@Override
	public CouchbaseClientFactory with(CouchbaseStuffHandle txOp) {
		return new SimpleCouchbaseClientFactory(cluster, bucket.name(), scope.name(), txOp);
	}

	@Override
	public CouchbaseStuffHandle getTransactionalOperator() {
		return (CouchbaseStuffHandle) transactionalOperator;
	}

	@Override
	public void close() {
		if (cluster instanceof OwnedSupplier) {
			cluster.get().disconnect();
		}
	}

	private static Duration now() {
		return Duration.of(System.nanoTime(), ChronoUnit.NANOS);
	}

}
