package com.salon.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

	Optional<Appointment> findByIdAndSalonId(UUID id, UUID salonId);

	Optional<Appointment> findByIdAndCustomerUserId(UUID id, UUID customerUserId);

	List<Appointment> findBySalonIdAndIdIn(UUID salonId, Collection<UUID> ids);

	List<Appointment> findBySalonIdAndBranchIdAndStatusInAndBlockEndAtAfter(UUID salonId, UUID branchId,
			Collection<AppointmentStatus> statuses, Instant after);

	List<Appointment> findBySalonIdAndStaffIdAndStatusInAndBlockEndAtAfter(UUID salonId, UUID staffId,
			Collection<AppointmentStatus> statuses, Instant after);

	List<Appointment> findBySalonIdAndStaffIdInAndStatusInAndBlockEndAtAfterAndStartAtBefore(UUID salonId,
			Collection<UUID> staffIds, Collection<AppointmentStatus> statuses, Instant from, Instant to);

	@Query("""
			select distinct a from Appointment a join a.items i
			where a.salonId = :salonId and i.serviceId = :serviceId and a.status in :statuses and a.blockEndAt > :after
			""")
	List<Appointment> findWithService(@Param("salonId") UUID salonId, @Param("serviceId") UUID serviceId,
			@Param("statuses") Collection<AppointmentStatus> statuses, @Param("after") Instant after);

	/** Blocking appointments of the staff members starting in {@code [from, to)} at the branch. */
	List<Appointment> findBySalonIdAndBranchIdAndStaffIdInAndStatusInAndStartAtGreaterThanEqualAndStartAtLessThan(
			UUID salonId, UUID branchId, Collection<UUID> staffIds, Collection<AppointmentStatus> statuses,
			Instant from, Instant to);

	/** Whether the customer has a blocking appointment overlapping {@code [start, end)}, except one. */
	@Query("""
			select count(a) > 0 from Appointment a
			where a.customerUserId = :userId and a.status in :statuses and a.startAt < :end and a.endAt > :start
			  and a.id <> :exceptId
			""")
	boolean customerHasOverlap(@Param("userId") UUID userId, @Param("statuses") Collection<AppointmentStatus> statuses,
			@Param("start") Instant start, @Param("end") Instant end, @Param("exceptId") UUID exceptId);

	long countByCustomerUserIdAndStatusInAndEndAtAfter(UUID customerUserId, Collection<AppointmentStatus> statuses,
			Instant after);

	Page<Appointment> findByCustomerUserIdAndStatusInAndEndAtAfter(UUID customerUserId,
			Collection<AppointmentStatus> statuses, Instant after, Pageable pageable);

	@Query("""
			select a from Appointment a
			where a.customerUserId = :userId and (a.status not in :blocking or a.endAt <= :now)
			""")
	Page<Appointment> findPastOfCustomer(@Param("userId") UUID userId,
			@Param("blocking") Collection<AppointmentStatus> blocking, @Param("now") Instant now, Pageable pageable);

}
