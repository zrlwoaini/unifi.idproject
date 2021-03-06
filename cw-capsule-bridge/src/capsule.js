import Axios from "axios";
import LinkHeader from "http-link-header";
try {
    var Write = require("write");
} catch (error) {
    Write = null;
}
try {
    var userHome = require("user-home");
} catch (error) {
    userHome = null;
}

import Log from "./log";

const CAPSULE_PARTIES_URI = "https://api.capsulecrm.com/api/v2/parties?perPage=100&embed=tags,fields,organisation";
const CAPSULE_FIELD_CLUB         = 368576;
const CAPSULE_FIELD_MEMBERTYPE   = 231465;
const CAPSULE_FIELD_MIFARENUMBER = 216824;
const CAPSULE_FIELD_RTCONTACTID  = 371942;

export default class Capsule {
    constructor(apiKey) {
        this.apiKey = apiKey;
        this._transformPerson = this._transformPerson.bind(this);
    }
    getPersons() {
        return this.getAllParties()
            .then((parties) => {
                if (userHome && Write) {
                    Write.sync(`${userHome}/capsuleParties.json`, JSON.stringify({data: parties}));
                }
                return parties.filter(this._isPerson);
            })
            .then((persons) => {
                let transformedPersons = persons.map(this._transformPerson);

                if (userHome && Write) {
                    let mifaresExport = "";
                    transformedPersons.forEach((person) => {
                        if (person.mifareNumber !== "") {
                            mifaresExport += `${person.mifareNumber}\n`;
                        }
                    });
                    Write.sync(`${userHome}/capsuleMifares.txt`, mifaresExport);
                }

                return transformedPersons;
            });
    }
    getAllParties() {
        return new Promise((resolve, reject) => {
            this._getPartyPages([], CAPSULE_PARTIES_URI, resolve, reject);
        });

    }
    _getPartyPages(soFar, uri, resolve, reject) {
        Log.info(`${soFar.length} parties, fetching '${uri}' ...`);
        Axios.get(uri, {
            headers: {
                Authorization: "Bearer " + this.apiKey,
                Accept: "application/json"
            }
        }).then((response) => {
            let nextLink = LinkHeader.parse(response.headers.link).get("rel", "next")[0];
            let parties = soFar.concat(response.data.parties);
            if (nextLink) {
                this._getPartyPages(parties, nextLink.uri, resolve, reject);
            } else {
                resolve(parties);
            }
        }).catch((error) => {
            Log.error(`${JSON.stringify(error)}`);
            resolve([]);
        });
    }
    _isPerson(party) {
        return party.type === "person";
    }
    _transformPerson(person) {
        return {
            ...person,
            club: this._getFieldValue(person, CAPSULE_FIELD_CLUB, ""),
            memberType: this._getFieldValue(person, CAPSULE_FIELD_MEMBERTYPE, ""),
            mifareNumber: this._getFieldValue(person, CAPSULE_FIELD_MIFARENUMBER, ""),
            rtContactId:  this._getFieldValue(person, CAPSULE_FIELD_RTCONTACTID,  "")
        };
    }
    _getFieldValue(entity, definitionId, defaultValue) {
        return (entity.fields.find((f) => f.definition.id === definitionId) || {}).value || defaultValue;
    }
}
