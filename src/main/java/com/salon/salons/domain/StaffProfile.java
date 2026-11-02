package com.salon.salons.domain;

/**
 * Public profile of a staff member.
 *
 * @param bookable whether customers can book appointments with this person
 */
public record StaffProfile(String displayName, String title, String bio, boolean bookable) {
}
