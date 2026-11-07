package com.salon.booking.web;

final class Texts {

	private Texts() {
	}

	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
