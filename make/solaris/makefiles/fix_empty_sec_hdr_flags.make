#
# Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#  
#

# Rules to build fix_empty_sec_hdr_flags, used by vm.make on Solaris

GENERATED                       = ../generated
FIX_EMPTY_SEC_HDR_FLAGS         = $(GENERATED)/fix_empty_sec_hdr_flags

FIX_EMPTY_SEC_HDR_FLAGS_DIR     = $(GAMMADIR)/src/os/solaris/fix_empty_sec_hdr_flags
FIX_EMPTY_SEC_HDR_FLAGS_SRC     = $(FIX_EMPTY_SEC_HDR_FLAGS_DIR)/fix_empty_sec_hdr_flags.c
FIX_EMPTY_SEC_HDR_FLAGS_FLAGS   = 
LIBS_FIX_EMPTY_SEC_HDR_FLAGS   += -lelf

ifeq ("${Platform_compiler}", "sparcWorks")
# Enable the following FIX_EMPTY_SEC_HDR_FLAGS_FLAGS addition if you need to
# compare the built ELF objects.
#
# The -g option makes static data global and the "-W0,-noglobal"
# option tells the compiler to not globalize static data using a unique
# globalization prefix. Instead force the use of a static globalization
# prefix based on the source filepath so the objects from two identical
# compilations are the same.
#
# Note: The blog says to use "-W0,-xglobalstatic", but that doesn't
#       seem to work. I got "-W0,-noglobal" from Kelly and that works.
#FIX_EMPTY_SEC_HDR_FLAGS_FLAGS += -W0,-noglobal
endif # Platform_compiler == sparcWorks

$(FIX_EMPTY_SEC_HDR_FLAGS): $(FIX_EMPTY_SEC_HDR_FLAGS_SRC)
	$(CC) -g -o $@ $< $(FIX_EMPTY_SEC_HDR_FLAGS_FLAGS) $(LIBS_FIX_EMPTY_SEC_HDR_FLAGS)