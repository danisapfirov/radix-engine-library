/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.atommodel.tokens;

import com.radixdlt.atommodel.tokens.MutableSupplyTokenDefinitionParticle.TokenTransition;
import com.radixdlt.atomos.SysCalls;
import com.radixdlt.atomos.ConstraintScrypt;
import com.radixdlt.atomos.Result;
import com.radixdlt.atommodel.routines.CreateFungibleTransitionRoutine;
import com.radixdlt.constraintmachine.WitnessValidator.WitnessValidatorResult;
import com.radixdlt.utils.UInt256;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Scrypt which defines how tokens are managed.
 */
public class TokensConstraintScrypt implements ConstraintScrypt {

	@Override
	public void main(SysCalls os) {
		os.registerParticle(
			MutableSupplyTokenDefinitionParticle.class,
			particle -> particle.getRRI().getAddress(),
			TokenDefinitionUtils::staticCheck,
			MutableSupplyTokenDefinitionParticle::getRRI
		);

		os.registerParticle(
			FixedSupplyTokenDefinitionParticle.class,
			particle -> particle.getRRI().getAddress(),
			TokenDefinitionUtils::staticCheck,
			FixedSupplyTokenDefinitionParticle::getRRI
		);

		os.registerParticle(
			UnallocatedTokensParticle.class,
			UnallocatedTokensParticle::getAddress,
			TokenDefinitionUtils::staticCheck,
			UnallocatedTokensParticle::getTokDefRef
		);

		os.registerParticle(
			TransferrableTokensParticle.class,
			TransferrableTokensParticle::getAddress,
			TokenDefinitionUtils::staticCheck,
			TransferrableTokensParticle::getTokDefRef,
			true
		);

		// Require Token Definition to be created with unallocated tokens of max supply
		os.createTransitionFromRRICombined(
			MutableSupplyTokenDefinitionParticle.class,
			UnallocatedTokensParticle.class,
			(tokDef, unallocated) -> {
				if (!Objects.equals(unallocated.getGranularity(), tokDef.getGranularity())) {
					return Result.error("Granularities not equal.");
				}
				if (!Objects.equals(unallocated.getTokenPermissions(), tokDef.getTokenPermissions())) {
					return Result.error("Permissions not equal.");
				}
				if (!unallocated.getAmount().equals(UInt256.MAX_VALUE)) {
					return Result.error("Unallocated amount must be UInt256.MAX_VALUE but was " + unallocated.getAmount());
				}

				return Result.success();
			}
		);

		os.createTransitionFromRRICombined(
			FixedSupplyTokenDefinitionParticle.class,
			TransferrableTokensParticle.class,
			(tokDef, transferrable) -> {
				if (!Objects.equals(tokDef.getGranularity(), transferrable.getGranularity())) {
					return Result.error("Granularities not equal.");
				}
				if (!Objects.equals(tokDef.getSupply(), transferrable.getAmount())) {
					return Result.error("Supply and amount are not equal.");
				}
				if (!transferrable.getTokenPermissions().isEmpty()) {
					return Result.error("Transferrable tokens of a fixed supply token must be empty.");
				}

				return Result.success();
			}
		);

		// Unallocated movement
		os.executeRoutine(new CreateFungibleTransitionRoutine<>(
			UnallocatedTokensParticle.class,
			UnallocatedTokensParticle.class,
			UnallocatedTokensParticle::getAmount,
			UnallocatedTokensParticle::getAmount,
			checkEquals(
				UnallocatedTokensParticle::getGranularity,
				UnallocatedTokensParticle::getGranularity,
				"Granularities not equal.",
				UnallocatedTokensParticle::getTokenPermissions,
				UnallocatedTokensParticle::getTokenPermissions,
				"Permissions not equal."
			),
			(in, meta) -> meta.isSignedBy(in.getTokDefRef().getAddress().getPublicKey())
				? WitnessValidatorResult.success() : WitnessValidatorResult.error("Permission not allowed.")
		));

		// Mint
		os.executeRoutine(new CreateFungibleTransitionRoutine<>(
			UnallocatedTokensParticle.class,
			TransferrableTokensParticle.class,
			UnallocatedTokensParticle::getAmount,
			TransferrableTokensParticle::getAmount,
			checkEquals(
				UnallocatedTokensParticle::getGranularity,
				TransferrableTokensParticle::getGranularity,
				"Granularities not equal.",
				UnallocatedTokensParticle::getTokenPermissions,
				TransferrableTokensParticle::getTokenPermissions,
				"Permissions not equal."
			),
			(in, meta) -> in.getTokenPermission(TokenTransition.MINT).check(in.getTokDefRef(), meta).isSuccess()
				? WitnessValidatorResult.success() : WitnessValidatorResult.error("Permission not allowed.")
		));

		// Transfers
		os.executeRoutine(new CreateFungibleTransitionRoutine<>(
			TransferrableTokensParticle.class,
			TransferrableTokensParticle.class,
			TransferrableTokensParticle::getAmount,
			TransferrableTokensParticle::getAmount,
			checkEquals(
				TransferrableTokensParticle::getGranularity,
				TransferrableTokensParticle::getGranularity,
				"Granularities not equal.",
				TransferrableTokensParticle::getTokenPermissions,
				TransferrableTokensParticle::getTokenPermissions,
				"Permissions not equal."
			),
			(in, meta) -> meta.isSignedBy(in.getAddress().getPublicKey())
				? WitnessValidatorResult.success() : WitnessValidatorResult.error("Permission not allowed.")
		));

		// Burns
		os.executeRoutine(new CreateFungibleTransitionRoutine<>(
			TransferrableTokensParticle.class,
			UnallocatedTokensParticle.class,
			TransferrableTokensParticle::getAmount,
			UnallocatedTokensParticle::getAmount,
			checkEquals(
				TransferrableTokensParticle::getGranularity,
				UnallocatedTokensParticle::getGranularity,
				"Granularities not equal.",
				TransferrableTokensParticle::getTokenPermissions,
				UnallocatedTokensParticle::getTokenPermissions,
				"Permissions not equal."
			),
			(in, meta) -> in.getTokenPermission(TokenTransition.BURN).check(in.getTokDefRef(), meta).isSuccess()
				? WitnessValidatorResult.success() : WitnessValidatorResult.error("Permission not allowed.")
		));
	}

	private static <T, U, V> BiFunction<T, U, Result> checkEquals(
		Function<T, V> firstMapper0, Function<U, V> firstMapper1, String firstErrorMessage,
		Function<T, V> secondMapper0, Function<U, V> secondMapper1, String secondErrorMessage
	) {
		return (t, u) -> {
			if (!Objects.equals(firstMapper0.apply(t), firstMapper1.apply(u))) {
				return Result.error(firstErrorMessage);
			}

			if (!Objects.equals(secondMapper0.apply(t), secondMapper1.apply(u))) {
				return Result.error(secondErrorMessage);
			}

			return Result.success();
		};
	}
}
