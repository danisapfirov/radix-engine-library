package com.radixdlt.constraintmachine;

import com.google.common.collect.ImmutableList;
import com.radixdlt.atoms.ImmutableAtom;
import com.radixdlt.atoms.SpunParticle;
import com.radixdlt.constraintmachine.TransitionProcedure.ProcedureResult;
import com.radixdlt.constraintmachine.WitnessValidator.WitnessValidatorResult;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;
import com.radixdlt.atoms.DataPointer;
import com.radixdlt.atoms.Particle;
import com.radixdlt.atoms.Spin;
import com.radixdlt.store.CMStores;
import com.radixdlt.common.EUID;
import com.radixdlt.serialization.SerializerId2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

public class ConstraintMachineTest {
	@SerializerId2("test.indexed_particle_2")
	private class IndexedParticle extends Particle {
		@Override
		public String toString() {
			return String.format("%s", getClass().getSimpleName());
		}
	}

	@Test
	public void when_validating_an_atom_with_particle_which_conflicts_with_virtual_state__an_internal_spin_conflict_is_returned() {
		ConstraintMachine machine = new ConstraintMachine.Builder()
			.virtualStore(state -> CMStores.virtualizeDefault(state, p -> true, Spin.UP))
			.build();

		IndexedParticle p = mock(IndexedParticle.class);
		when(p.getDestinations()).thenReturn(Collections.singleton(EUID.ONE));

		CMAtom atom = mock(CMAtom.class);
		when(atom.getParticles()).thenReturn(ImmutableList.of(
			new CMParticle(p, DataPointer.ofParticle(0, 0), Spin.NEUTRAL, 1)
		));
		when(atom.getAtom()).thenReturn(mock(ImmutableAtom.class));

		assertThat(machine.validate(atom))
			.contains(new CMError(DataPointer.ofParticle(0, 0), CMErrorCode.INTERNAL_SPIN_CONFLICT));
	}

	@Test
	public void when_validating_a_2_input_1_output_particle_group_which_pops_1_input_first__validation_should_succeed() {
		TransitionProcedure<Particle, Particle> procedure = mock(TransitionProcedure.class);
		when(procedure.execute(any(), any(), any(), any()))
			.thenReturn(ProcedureResult.popInput(new Object()))
			.thenReturn(ProcedureResult.popInputOutput());

		ConstraintMachine machine = new ConstraintMachine.Builder()
			.setParticleProcedures((p0, p1) -> procedure)
			.setWitnessValidators((p0, p1) -> (res, v0, v1, meta) -> WitnessValidatorResult.success())
			.build();

		Optional<CMError> errors = machine.validateParticleGroup(
			ImmutableList.of(
				SpunParticle.down(mock(Particle.class)),
				SpunParticle.down(mock(Particle.class)),
				SpunParticle.up(mock(Particle.class))
			),
			0,
			mock(AtomMetadata.class)
		);

		assertThat(errors).isEmpty();
	}
}