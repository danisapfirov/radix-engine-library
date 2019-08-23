package com.radixdlt.atomos;

import com.radixdlt.atoms.ImmutableAtom;
import com.radixdlt.crypto.Hash;
import com.radixdlt.engine.CMSuccessHook;
import com.radixdlt.universe.Universe;
import com.radixdlt.utils.POW;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class AtomCheckHook implements CMSuccessHook<SimpleRadixEngineAtom> {
	private final boolean skipAtomFeeCheck;
	private final Supplier<Universe> universeSupplier;
	private final LongSupplier timestampSupplier;
	private static final Hash DEFAULT_TARGET = new Hash("0000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
	private final int maximumDrift;

	public AtomCheckHook(
		Supplier<Universe> universeSupplier,
		LongSupplier timestampSupplier,
		boolean skipAtomFeeCheck,
		int maximumDrift
	) {
		this.universeSupplier = universeSupplier;
		this.timestampSupplier = timestampSupplier;
		this.skipAtomFeeCheck = skipAtomFeeCheck;
		this.maximumDrift = maximumDrift;
	}

	@Override
	public Result hook(SimpleRadixEngineAtom cmAtom) {
		if (cmAtom.getCMInstruction().getParticles().isEmpty()) {
			return Result.error("atom has no particles");
		}

		final boolean isMagic = Objects.equals(cmAtom.getAtom().getMetaData().get("magic"), "0xdeadbeef");

		// Atom has fee
		if (!skipAtomFeeCheck) {
			if (universeSupplier.get().getGenesis().stream().map(ImmutableAtom::getAID).noneMatch(cmAtom.getAtom().getAID()::equals)
				&& !isMagic) {

				String powNonceString = cmAtom.getAtom().getMetaData().get(ImmutableAtom.METADATA_POW_NONCE_KEY);
				if (powNonceString == null) {
					return Result.error("atom fee missing, metadata does not contain '" + ImmutableAtom.METADATA_POW_NONCE_KEY + "'");
				}

				final long powNonce;
				try {
					powNonce = Long.parseLong(powNonceString);
				} catch (NumberFormatException e) {
					return Result.error("atom fee invalid, metadata contains invalid powNonce: " + powNonceString);
				}

				final Hash powFeeHash = cmAtom.getAtom().copyExcludingMetadata(ImmutableAtom.METADATA_POW_NONCE_KEY).getHash();
				POW pow = new POW(universeSupplier.get().getMagic(), powFeeHash, powNonce);
				Result powResult = checkPow(pow, powFeeHash, DEFAULT_TARGET, universeSupplier.get().getMagic());
				if (powResult.isError()) {
					return powResult;
				}
			}
		}

		String timestampString = cmAtom.getAtom().getMetaData().get(ImmutableAtom.METADATA_TIMESTAMP_KEY);
		if (timestampString == null) {
			return Result.error("atom metadata does not contain '" + ImmutableAtom.METADATA_TIMESTAMP_KEY + "'");
		}
		try {
			long timestamp = Long.parseLong(timestampString);

			if (timestamp > timestampSupplier.getAsLong()
				+ TimeUnit.MILLISECONDS.convert(maximumDrift, TimeUnit.SECONDS)) {
				return Result.error("atom metadata timestamp is after allowed drift time");
			}
			if (timestamp < universeSupplier.get().getTimestamp()) {
				return Result.error("atom metadata timestamp is before universe creation");
			}
		} catch (NumberFormatException e) {
			return Result.error("atom metadata contains invalid timestamp: " + timestampString);
		}

		return Result.success();
	}

	private static Result checkPow(POW pow, Hash hash, Hash target, int universeMagic) {
		if (!pow.getSeed().equals(hash)) {
			return Result.error("atom fee invalid: seed does not match validation parameter seed (" + pow.getSeed() + ") + hash(" + hash + ")");
		}

		if (pow.getMagic() != universeMagic) {
			return Result.error("atom fee invalid: magic '" + pow.getMagic() + "' does not match Universe magic '");
		}

		if (pow.getHash().compareTo(target) >= 0) {
			return Result.error("atom fee invalid: '" + pow.getHash() + "' does not meet target '" + target + "'");
		}

		return Result.success();
	}
}
