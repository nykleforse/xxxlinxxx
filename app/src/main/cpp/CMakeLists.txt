cmake_minimum_required(VERSION 3.22.1)
project("xxxlinkxxx")

set(CODEC2_DIR ${CMAKE_SOURCE_DIR}/codec2)
set(CODEC2_CODEBOOK_DIR ${CODEC2_DIR}/src/codebook)
set(CODEC2_GENERATED_DIR ${CMAKE_BINARY_DIR}/generated/codec2)

file(MAKE_DIRECTORY ${CODEC2_GENERATED_DIR})

function(generate_codec2_codebook output array_name)
    set(values)
    set(codebook_index 0)
    foreach(input_file IN LISTS ARGN)
        file(STRINGS ${input_file} lines)
        set(numbers)
        foreach(line IN LISTS lines)
            string(REGEX REPLACE "#.*$" "" clean_line "${line}")
            string(REGEX MATCHALL "[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?" line_numbers "${clean_line}")
            list(APPEND numbers ${line_numbers})
        endforeach()

        list(LENGTH numbers number_count)
        if(number_count LESS 2)
            message(FATAL_ERROR "Invalid Codec2 codebook: ${input_file}")
        endif()

        list(GET numbers 0 k)
        list(GET numbers 1 m)
        math(EXPR data_count "${k} * ${m}")
        math(EXPR expected_count "${data_count} + 2")
        if(number_count LESS expected_count)
            message(FATAL_ERROR "Codec2 codebook ${input_file} has ${number_count} values, expected ${expected_count}")
        endif()

        math(EXPR codebook_index "${codebook_index}+1")
        math(EXPR code_index "${codebook_index}-1")
        math(EXPR log2m "0")
        set(tmp_m ${m})
        while(tmp_m GREATER 1)
            math(EXPR tmp_m "${tmp_m} / 2")
            math(EXPR log2m "${log2m} + 1")
        endwhile()

        string(APPEND arrays "/* ${input_file} */\n")
        string(APPEND arrays "static float codes${code_index}[] = {\n")
        foreach(i RANGE 0 ${data_count})
            if(i EQUAL data_count)
                break()
            endif()
            math(EXPR value_index "${i} + 2")
            list(GET numbers ${value_index} value)
            string(APPEND arrays "  ${value}")
            math(EXPR row_end "(${i} + 1) % ${k}")
            if(i LESS data_count)
                string(APPEND arrays ",")
            endif()
            if(row_end EQUAL 0)
                string(APPEND arrays "\n")
            endif()
        endforeach()
        string(APPEND arrays "};\n\n")

        string(APPEND structs "  /* ${input_file} */\n")
        string(APPEND structs "  {\n")
        string(APPEND structs "    ${k},\n")
        string(APPEND structs "    ${log2m},\n")
        string(APPEND structs "    ${m},\n")
        string(APPEND structs "    codes${code_index}\n")
        string(APPEND structs "  },\n")
    endforeach()

    file(WRITE ${output}
"/* THIS IS A GENERATED FILE. Edit CMakeLists.txt and Codec2 codebook inputs. */

#include \"defines.h\"

${arrays}
const struct lsp_codebook ${array_name}[] = {
${structs}  { 0, 0, 0, 0 }
};
")
endfunction()

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebook.c lsp_cb
    ${CODEC2_CODEBOOK_DIR}/lsp1.txt
    ${CODEC2_CODEBOOK_DIR}/lsp2.txt
    ${CODEC2_CODEBOOK_DIR}/lsp3.txt
    ${CODEC2_CODEBOOK_DIR}/lsp4.txt
    ${CODEC2_CODEBOOK_DIR}/lsp5.txt
    ${CODEC2_CODEBOOK_DIR}/lsp6.txt
    ${CODEC2_CODEBOOK_DIR}/lsp7.txt
    ${CODEC2_CODEBOOK_DIR}/lsp8.txt
    ${CODEC2_CODEBOOK_DIR}/lsp9.txt
    ${CODEC2_CODEBOOK_DIR}/lsp10.txt
)

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebookd.c lsp_cbd
    ${CODEC2_CODEBOOK_DIR}/dlsp1.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp2.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp3.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp4.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp5.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp6.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp7.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp8.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp9.txt
    ${CODEC2_CODEBOOK_DIR}/dlsp10.txt
)

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebookjmv.c lsp_cbjmv
    ${CODEC2_CODEBOOK_DIR}/lspjmv1.txt
    ${CODEC2_CODEBOOK_DIR}/lspjmv2.txt
    ${CODEC2_CODEBOOK_DIR}/lspjmv3.txt
)

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebookge.c ge_cb
    ${CODEC2_CODEBOOK_DIR}/gecb.txt
)

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebooknewamp1.c newamp1vq_cb
    ${CODEC2_CODEBOOK_DIR}/train_120_1.txt
    ${CODEC2_CODEBOOK_DIR}/train_120_2.txt
)

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebooknewamp1_energy.c newamp1_energy_cb
    ${CODEC2_CODEBOOK_DIR}/newamp1_energy_q.txt
)

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebooknewamp2.c newamp2vq_cb
    ${CODEC2_CODEBOOK_DIR}/codes_450.txt
)

generate_codec2_codebook(${CODEC2_GENERATED_DIR}/codebooknewamp2_energy.c newamp2_energy_cb
    ${CODEC2_CODEBOOK_DIR}/newamp2_energy_q.txt
)

add_library(codec2_core STATIC
    ${CODEC2_DIR}/src/codec2.c
    ${CODEC2_DIR}/src/codec2_fft.c
    ${CODEC2_DIR}/src/dump.c
    ${CODEC2_DIR}/src/interp.c
    ${CODEC2_DIR}/src/kiss_fft.c
    ${CODEC2_DIR}/src/kiss_fftr.c
    ${CODEC2_DIR}/src/lpc.c
    ${CODEC2_DIR}/src/lsp.c
    ${CODEC2_DIR}/src/mbest.c
    ${CODEC2_DIR}/src/newamp1.c
    ${CODEC2_DIR}/src/nlp.c
    ${CODEC2_DIR}/src/pack.c
    ${CODEC2_DIR}/src/phase.c
    ${CODEC2_DIR}/src/postfilter.c
    ${CODEC2_DIR}/src/quantise.c
    ${CODEC2_DIR}/src/sine.c
    ${CODEC2_GENERATED_DIR}/codebook.c
    ${CODEC2_GENERATED_DIR}/codebookd.c
    ${CODEC2_GENERATED_DIR}/codebookjmv.c
    ${CODEC2_GENERATED_DIR}/codebookge.c
    ${CODEC2_GENERATED_DIR}/codebooknewamp1.c
    ${CODEC2_GENERATED_DIR}/codebooknewamp1_energy.c
    ${CODEC2_GENERATED_DIR}/codebooknewamp2.c
    ${CODEC2_GENERATED_DIR}/codebooknewamp2_energy.c
)

target_include_directories(codec2_core PUBLIC
    ${CODEC2_DIR}
    ${CODEC2_DIR}/src
)

add_library(codec2_bridge SHARED
    codec2_bridge.cpp
)

target_link_options(codec2_bridge PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_include_directories(codec2_bridge PRIVATE
    ${CODEC2_DIR}/src
    ${CODEC2_DIR}
)

find_library(log-lib log)

target_link_libraries(
    codec2_bridge
    codec2_core
    ${log-lib}
)
