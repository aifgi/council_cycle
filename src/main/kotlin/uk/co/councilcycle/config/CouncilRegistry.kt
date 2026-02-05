package uk.co.councilcycle.config

import uk.co.councilcycle.model.Council

object CouncilRegistry {

    fun loadCouncils(): List<Council> = DEFAULT_COUNCILS

    private val DEFAULT_COUNCILS = listOf(
        Council(
            id = "oxford",
            name = "Oxford City Council",
            meetingsUrl = "https://mycouncil.oxford.gov.uk/mgListCommittees.aspx",
            region = "Oxfordshire",
        ),
        Council(
            id = "cambridge",
            name = "Cambridge City Council",
            meetingsUrl = "https://democracy.cambridge.gov.uk/mgListCommittees.aspx",
            region = "Cambridgeshire",
        ),
        Council(
            id = "bristol",
            name = "Bristol City Council",
            meetingsUrl = "https://democracy.bristol.gov.uk/mgListCommittees.aspx",
            region = "Bristol",
        ),
        Council(
            id = "manchester",
            name = "Manchester City Council",
            meetingsUrl = "https://democracy.manchester.gov.uk/mgListCommittees.aspx",
            region = "Greater Manchester",
        ),
        Council(
            id = "birmingham",
            name = "Birmingham City Council",
            meetingsUrl = "https://birmingham.cmis.uk.com/birmingham/Committees.aspx",
            region = "West Midlands",
        ),
        Council(
            id = "leeds",
            name = "Leeds City Council",
            meetingsUrl = "https://democracy.leeds.gov.uk/mgListCommittees.aspx",
            region = "West Yorkshire",
        ),
        Council(
            id = "nottingham",
            name = "Nottingham City Council",
            meetingsUrl = "https://committee.nottinghamcity.gov.uk/mgListCommittees.aspx",
            region = "Nottinghamshire",
        ),
        Council(
            id = "brighton",
            name = "Brighton & Hove City Council",
            meetingsUrl = "https://democracy.brighton-hove.gov.uk/mgListCommittees.aspx",
            region = "East Sussex",
        ),
        Council(
            id = "york",
            name = "City of York Council",
            meetingsUrl = "https://democracy.york.gov.uk/mgListCommittees.aspx",
            region = "North Yorkshire",
        ),
        Council(
            id = "bath",
            name = "Bath & North East Somerset Council",
            meetingsUrl = "https://democracy.bathnes.gov.uk/mgListCommittees.aspx",
            region = "Somerset",
        ),
    )
}
