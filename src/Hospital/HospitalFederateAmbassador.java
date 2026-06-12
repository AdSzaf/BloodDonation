package Hospital;

import hla.rti1516e.*;
import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.HLAASCIIstring;
import hla.rti1516e.encoding.HLAfloat32BE;
import hla.rti1516e.encoding.HLAfloat64BE;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.time.HLAfloat64Time;
import org.portico.impl.hla1516e.types.encoding.HLA1516eASCIIstring;
import org.portico.impl.hla1516e.types.encoding.HLA1516eFloat32BE;
import org.portico.impl.hla1516e.types.encoding.HLA1516eFloat64BE;
import org.portico.impl.hla1516e.types.encoding.HLA1516eInteger32BE;

/**
 * Ambassador federatu Hospital.
 *
 * Odbiera BloodTransported i:
 *  1. Sprawdza czy dostawa jest dla tego szpitala (hospitalId).
 *  2. Symuluje separacje krwi na skladniki (wywoluje Hospital.separateBlood).
 *  3. Oblicza wiek krwi = czas_obecny - donationTime.
 *  4. Aktualizuje statystyki sredniego wieku i wskaznika niedoboru.
 */
public class HospitalFederateAmbassador extends NullFederateAmbassador {

	private final HospitalFederate federate;

	protected double  federateTime      = 0.0;
	protected double  federateLookahead = 1.0;

	protected boolean isRegulating  = false;
	protected boolean isConstrained = false;
	protected boolean isAdvancing   = false;
	protected boolean isAnnounced   = false;
	protected boolean isReadyToRun  = false;
	protected boolean isRunning     = true;

	public HospitalFederateAmbassador(HospitalFederate federate) {
		this.federate = federate;
	}

	private void log(String msg) {
		System.out.println("[" + federate.getFederateName() + " Amb] : " + msg);

	}

	// -----------------------------------------------------------------------
	// Synchronizacja
	// -----------------------------------------------------------------------

	@Override
	public void synchronizationPointRegistrationFailed(String label,
													   SynchronizationPointFailureReason reason) {
		log("Blad rejestracji punktu sync: " + label + ", powod=" + reason);
	}

	@Override
	public void synchronizationPointRegistrationSucceeded(String label) {
		log("Zarejestrowano punkt sync: " + label);
	}

	@Override
	public void announceSynchronizationPoint(String label, byte[] tag) {
		log("Ogloszono punkt sync: " + label);
		if (label.equals(HospitalFederate.READY_TO_RUN))
			this.isAnnounced = true;
	}

	@Override
	public void federationSynchronized(String label, FederateHandleSet failed) {
		log("Federacja zsynchronizowana: " + label);
		if (label.equals(HospitalFederate.READY_TO_RUN))
			this.isReadyToRun = true;
	}

	// -----------------------------------------------------------------------
	// Czas
	// -----------------------------------------------------------------------

	@Override
	public void timeRegulationEnabled(LogicalTime time) {
		this.federateTime = ((HLAfloat64Time) time).getValue();
		this.isRegulating = true;
	}

	@Override
	public void timeConstrainedEnabled(LogicalTime time) {
		this.federateTime = ((HLAfloat64Time) time).getValue();
		this.isConstrained = true;
	}

	@Override
	public void timeAdvanceGrant(LogicalTime time) {
		this.federateTime = ((HLAfloat64Time) time).getValue();
		this.isAdvancing = false;
	}

	// -----------------------------------------------------------------------
	// Odbior interakcji
	// -----------------------------------------------------------------------

	@Override
	public void receiveInteraction(InteractionClassHandle interactionClass,
								   ParameterHandleValueMap theParameters,
								   byte[] tag,
								   OrderType sentOrdering,
								   TransportationTypeHandle theTransport,
								   SupplementalReceiveInfo receiveInfo)
			throws FederateInternalError {
		receiveInteraction(interactionClass, theParameters, tag,
				sentOrdering, theTransport, null, sentOrdering, receiveInfo);
	}

	@Override
	public void receiveInteraction(InteractionClassHandle interactionClass,
								   ParameterHandleValueMap theParameters,
								   byte[] tag,
								   OrderType sentOrdering,
								   TransportationTypeHandle theTransport,
								   LogicalTime time,
								   OrderType receivedOrdering,
								   SupplementalReceiveInfo receiveInfo)
			throws FederateInternalError {

		// ---- BloodTransported ----------------------------------------------
		if (interactionClass.equals(federate.bloodTransportedHandle)) {
			try {
				HLAinteger32BE decBloodId = new HLA1516eInteger32BE();
				decBloodId.decode(theParameters.get(federate.btBloodIdHandle));
				int bloodId = decBloodId.getValue();

				HLAinteger32BE decHospId = new HLA1516eInteger32BE();
				decHospId.decode(theParameters.get(federate.btHospitalIdHandle));
				int targetHospitalId = decHospId.getValue();

				// Ignoruj dostawy przeznaczone dla innych szpitali
				// (w realnej federacji kazdyby szpital mial osobny federat)
				if (federate.hospital != null
						&& targetHospitalId != federate.hospital.getHospitalId()) {
					return;
				}

				HLAfloat32BE decAmount = new HLA1516eFloat32BE();
				decAmount.decode(theParameters.get(federate.btBloodAmountHandle));
				float bloodAmount = decAmount.getValue();

				HLAASCIIstring decType = new HLA1516eASCIIstring();
				decType.decode(theParameters.get(federate.btBloodTypeHandle));
				String bloodType = decType.getValue();

				HLAfloat64BE decDonTime = new HLA1516eFloat64BE();
				decDonTime.decode(theParameters.get(federate.btDonationTimeHandle));
				double donationTime = decDonTime.getValue();

				double currentTime = this.federateTime;
				double bloodAgeDays = currentTime - donationTime;

				log("Odebrano BloodTransported: id=" + bloodId
						+ ", typ=" + bloodType
						+ ", ilosc=" + bloodAmount
						+ ", wiek_krwi=" + String.format("%.1f", bloodAgeDays) + " dni");

				// Separacja krwi na skladniki + aktualizacja statystyk
				if (federate.hospital != null) {
					federate.hospital.separateBlood(bloodAmount, currentTime, donationTime);

					// Oznacz najstarsze otwarte zamowienie jako zrealizowane
					// -> usunie je z pendingRequests zanim checkTimeouts policzy jako niedobor
					federate.hospital.registerDelivery();

					log("Szpital #" + targetHospitalId
							+ " | Sredni wiek krwi: "
							+ String.format("%.2f", federate.hospital.getAverageBloodAgeDays())
							+ " dni | Wskaznik niedoboru: "
							+ String.format("%.1f", federate.hospital.getShortageRate()) + "%"
							+ " | Oczekujace zamowienia: " + federate.hospital.getPendingCount());
				}

			} catch (DecoderException e) {
				log("Blad dekodowania BloodTransported: " + e.getMessage());
			}
		}
	}

	// -----------------------------------------------------------------------
	// Obiekty (Hospital nie publikuje obiektow)
	// -----------------------------------------------------------------------

	@Override
	public void discoverObjectInstance(ObjectInstanceHandle theObject,
									   ObjectClassHandle theObjectClass,
									   String objectName) throws FederateInternalError {
		log("Odkryto obiekt: " + objectName);
	}

	@Override
	public void removeObjectInstance(ObjectInstanceHandle theObject,
									 byte[] tag,
									 OrderType sentOrdering,
									 SupplementalRemoveInfo removeInfo) throws FederateInternalError {
		log("Usunieto obiekt: " + theObject);
	}
}